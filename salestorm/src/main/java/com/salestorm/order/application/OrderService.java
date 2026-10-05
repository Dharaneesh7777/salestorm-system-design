package com.salestorm.order.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.order.application.dto.CreateOrderCommand;
import com.salestorm.order.application.dto.OrderItemDto;
import com.salestorm.order.application.dto.OrderResult;
import com.salestorm.order.domain.OrderRepository;
import com.salestorm.order.domain.OrderStatus;
import com.salestorm.order.domain.OrderStatusHistoryRepository;
import com.salestorm.order.domain.state.OrderState;
import com.salestorm.order.infrastructure.entity.OrderEntity;
import com.salestorm.order.infrastructure.entity.OrderItemEntity;
import com.salestorm.order.infrastructure.entity.OrderStatusHistoryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service managing Order aggregate lifecycle, state machine transitions,
 * audit history, and outbox event emissions.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderService(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository historyRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderResult onCreateOrder(CreateOrderCommand command, String eventId) {
        Optional<OrderEntity> existingOpt = orderRepository.findById(command.orderId());
        if (existingOpt.isPresent()) {
            log.info("Order {} already exists, returning existing instance", command.orderId());
            return toResult(existingOpt.get());
        }

        OrderEntity order = new OrderEntity(
                command.orderId(),
                command.customerId(),
                command.reservationId(),
                OrderStatus.CREATED,
                command.totalAmount(),
                command.currency() != null ? command.currency() : "USD"
        );

        if (command.items() != null) {
            for (OrderItemDto itemDto : command.items()) {
                OrderItemEntity item = new OrderItemEntity(
                        order,
                        itemDto.productId(),
                        itemDto.quantity(),
                        itemDto.unitPrice(),
                        itemDto.lineTotal()
                );
                order.addItem(item);
            }
        }

        OrderStatusHistoryEntity history = new OrderStatusHistoryEntity(
                order,
                null,
                OrderStatus.CREATED.name(),
                "Order created",
                eventId
        );
        order.addHistory(history);

        order = orderRepository.save(order);

        saveOutboxEvent(order.getOrderId(), "OrderCreated", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "orderId", order.getOrderId(),
                "customerId", order.getCustomerId(),
                "reservationId", order.getReservationId() != null ? order.getReservationId() : "",
                "totalAmount", order.getTotalAmount().toString(),
                "status", OrderStatus.CREATED.name()
        ));

        return toResult(order);
    }

    @Transactional
    public OrderResult onPaymentInitiated(String orderId, String paymentId, String eventId) {
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            log.warn("Cannot transition non-existent order {} to PAYMENT_PENDING", orderId);
            return null;
        }

        OrderState currentState = OrderState.from(order.getStatus());
        OrderState nextState = currentState.initiatePayment();

        if (nextState.getStatus() != order.getStatus()) {
            String oldStatus = order.getStatus().name();
            order.setStatus(nextState.getStatus());
            order.setUpdatedAt(Instant.now());

            OrderStatusHistoryEntity history = new OrderStatusHistoryEntity(
                    order,
                    oldStatus,
                    nextState.getStatus().name(),
                    "Payment initiated: " + paymentId,
                    eventId
            );
            order.addHistory(history);
            orderRepository.save(order);
        }

        return toResult(order);
    }

    @Transactional
    public OrderResult onConfirmOrder(
            String orderId,
            String paymentId,
            String reservationId,
            BigDecimal amount,
            Long customerId,
            String eventId
    ) {
        Optional<OrderEntity> orderOpt = orderRepository.findById(orderId);
        OrderEntity order;

        if (orderOpt.isEmpty()) {
            // Out-of-order event: ConfirmOrder arrived before CreateOrder!
            log.info("Out-of-order event: Creating and confirming order {} directly", orderId);
            order = new OrderEntity(
                    orderId,
                    customerId != null ? customerId : 1001L,
                    reservationId,
                    OrderStatus.CONFIRMED,
                    amount != null ? amount : new BigDecimal("99.99"),
                    "USD"
            );
            OrderStatusHistoryEntity history = new OrderStatusHistoryEntity(
                    order,
                    null,
                    OrderStatus.CONFIRMED.name(),
                    "Order created and confirmed via payment receipt: " + paymentId,
                    eventId
            );
            order.addHistory(history);
            order = orderRepository.save(order);
        } else {
            order = orderOpt.get();
            OrderState currentState = OrderState.from(order.getStatus());
            OrderState nextState = currentState.confirm();

            if (nextState.getStatus() != order.getStatus()) {
                String oldStatus = order.getStatus().name();
                order.setStatus(nextState.getStatus());
                order.setUpdatedAt(Instant.now());

                OrderStatusHistoryEntity history = new OrderStatusHistoryEntity(
                        order,
                        oldStatus,
                        nextState.getStatus().name(),
                        "Order confirmed via payment: " + paymentId,
                        eventId
                );
                order.addHistory(history);
                order = orderRepository.save(order);
            }
        }

        saveOutboxEvent(orderId, "OrderConfirmed", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "orderId", orderId,
                "reservationId", order.getReservationId() != null ? order.getReservationId() : "",
                "paymentId", paymentId != null ? paymentId : "",
                "status", OrderStatus.CONFIRMED.name()
        ));

        return toResult(order);
    }

    @Transactional
    public OrderResult onCancelOrder(String orderId, String reason, String eventId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            return toResult(order); // Idempotent
        }

        OrderState currentState = OrderState.from(order.getStatus());
        OrderState nextState = currentState.cancel(reason);

        String oldStatus = order.getStatus().name();
        order.setStatus(nextState.getStatus());
        order.setCancelReason(reason);
        order.setUpdatedAt(Instant.now());

        OrderStatusHistoryEntity history = new OrderStatusHistoryEntity(
                order,
                oldStatus,
                nextState.getStatus().name(),
                reason,
                eventId
        );
        order.addHistory(history);
        order = orderRepository.save(order);

        saveOutboxEvent(orderId, "OrderCancelled", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "orderId", orderId,
                "reservationId", order.getReservationId() != null ? order.getReservationId() : "",
                "reason", reason != null ? reason : "",
                "status", OrderStatus.CANCELLED.name()
        ));

        return toResult(order);
    }

    @Transactional
    public OrderResult updateOrderStatus(String orderId, OrderStatus targetStatus, String reason, String eventId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        OrderState currentState = OrderState.from(order.getStatus());
        OrderState nextState = switch (targetStatus) {
            case PROCESSING -> currentState.process();
            case SHIPPED -> currentState.ship();
            case OUT_FOR_DELIVERY -> currentState.outForDelivery();
            case DELIVERED -> currentState.deliver();
            case CANCELLED -> currentState.cancel(reason);
            default -> currentState;
        };

        if (nextState.getStatus() != order.getStatus()) {
            String oldStatus = order.getStatus().name();
            order.setStatus(nextState.getStatus());
            order.setUpdatedAt(Instant.now());

            OrderStatusHistoryEntity history = new OrderStatusHistoryEntity(
                    order,
                    oldStatus,
                    nextState.getStatus().name(),
                    reason != null ? reason : "Status transitioned to " + nextState.getStatus().name(),
                    eventId
            );
            order.addHistory(history);
            order = orderRepository.save(order);

            saveOutboxEvent(orderId, "Order" + capitalize(nextState.getStatus().name()), Map.of(
                    "eventId", UUID.randomUUID().toString(),
                    "orderId", orderId,
                    "status", nextState.getStatus().name()
            ));
        }

        return toResult(order);
    }

    @Transactional(readOnly = true)
    public Optional<OrderResult> getOrderById(String orderId) {
        return orderRepository.findById(orderId).map(this::toResult);
    }

    private void saveOutboxEvent(String orderId, String eventType, Map<String, Object> payloadMap) {
        String eventPayload;
        try {
            eventPayload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }

        OutboxEntity outbox = new OutboxEntity(
                UUID.randomUUID().toString(),
                "ORDERS",
                orderId,
                orderId,
                eventType,
                1,
                eventPayload
        );
        outboxRepository.save(outbox);
    }

    private OrderResult toResult(OrderEntity entity) {
        List<OrderItemDto> itemDtos = entity.getItems().stream()
                .map(item -> new OrderItemDto(
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getLineTotal()
                ))
                .toList();

        List<OrderResult.OrderStatusHistoryDto> historyDtos = entity.getHistory().stream()
                .map(h -> new OrderResult.OrderStatusHistoryDto(
                        h.getOldStatus(),
                        h.getNewStatus(),
                        h.getReason(),
                        h.getEventId(),
                        h.getChangedAt()
                ))
                .toList();

        return new OrderResult(
                entity.getOrderId(),
                entity.getCustomerId(),
                entity.getReservationId(),
                entity.getStatus(),
                entity.getTotalAmount(),
                entity.getCurrency(),
                entity.getCancelReason(),
                entity.getCreatedAt(),
                itemDtos,
                historyDtos
        );
    }

    private String capitalize(String name) {
        if (name == null || name.isEmpty()) return "";
        return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
    }
}

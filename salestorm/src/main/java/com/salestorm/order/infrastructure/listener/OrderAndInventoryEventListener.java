package com.salestorm.order.infrastructure.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.domain.event.ProcessedEventRepository;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.domain.ReleaseReason;
import com.salestorm.order.application.OrderService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Event-driven bridge connecting Payment, Order, and Inventory domain aggregates.
 * Wires:
 * - PaymentInitiated -> Order onPaymentInitiated
 * - PaymentSucceeded -> Inventory confirm & Order onConfirmOrder
 * - PaymentFailed -> Inventory release & Order onCancelOrder
 * - OrderConfirmed -> Inventory markSold
 */
@Component
public class OrderAndInventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderAndInventoryEventListener.class);
    private static final String CONSUMER_NAME = "OrderAndInventoryEventListener";

    private final EventBus eventBus;
    private final ProcessedEventRepository processedEventRepository;
    private final ReservationService reservationService;
    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public OrderAndInventoryEventListener(
            EventBus eventBus,
            ProcessedEventRepository processedEventRepository,
            ReservationService reservationService,
            OrderService orderService,
            ObjectMapper objectMapper
    ) {
        this.eventBus = eventBus;
        this.processedEventRepository = processedEventRepository;
        this.reservationService = reservationService;
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void register() {
        eventBus.subscribe("PaymentInitiated", this::handlePaymentInitiated);
        eventBus.subscribe("PaymentSucceeded", this::handlePaymentSucceeded);
        eventBus.subscribe("PaymentFailed", this::handlePaymentFailed);
        eventBus.subscribe("OrderConfirmed", this::handleOrderConfirmed);
        eventBus.subscribe("ConfirmOrder", this::handleConfirmOrderCommand);
        eventBus.subscribe("CancelOrder", this::handleCancelOrderCommand);
        eventBus.subscribe("MarkOrderPaymentPending", this::handlePaymentInitiated);
    }

    public void handlePaymentInitiated(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME)) {
            return;
        }

        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String paymentId = (String) payload.get("paymentId");

        if (orderId != null && !orderId.isBlank()) {
            orderService.onPaymentInitiated(orderId, paymentId, envelope.eventId());
        }
    }

    public void handlePaymentSucceeded(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME)) {
            return;
        }

        Map<String, Object> payload = parsePayload(envelope.payload());
        String reservationId = (String) payload.get("reservationId");
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String paymentId = (String) payload.get("paymentId");
        String amountStr = (String) payload.get("amount");
        BigDecimal amount = amountStr != null && !amountStr.isBlank() ? new BigDecimal(amountStr) : null;

        // 1. Confirm inventory reservation (protected from expiry)
        if (reservationId != null && !reservationId.isBlank()) {
            try {
                reservationService.confirm(reservationId);
            } catch (Exception e) {
                log.warn("Inventory confirm for reservation {} failed on event {}: {}", reservationId, envelope.eventId(), e.getMessage());
            }
        }

        // 2. Confirm order (creates order if absent on out-of-order delivery)
        if (orderId != null && !orderId.isBlank()) {
            try {
                orderService.onConfirmOrder(orderId, paymentId, reservationId, amount, null, envelope.eventId());
            } catch (Exception e) {
                log.error("Order confirmation failed for order {} on event {}: {}", orderId, envelope.eventId(), e.getMessage(), e);
            }
        }
    }

    public void handlePaymentFailed(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME)) {
            return;
        }

        Map<String, Object> payload = parsePayload(envelope.payload());
        String reservationId = (String) payload.get("reservationId");
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());

        // 1. Release inventory reservation back to available pool
        if (reservationId != null && !reservationId.isBlank()) {
            try {
                reservationService.release(reservationId, ReleaseReason.PAYMENT_FAILED);
            } catch (Exception e) {
                log.warn("Inventory release for reservation {} failed on event {}: {}", reservationId, envelope.eventId(), e.getMessage());
            }
        }

        // 2. Cancel order
        if (orderId != null && !orderId.isBlank()) {
            try {
                orderService.onCancelOrder(orderId, "Payment Failed", envelope.eventId());
            } catch (Exception e) {
                log.warn("Order cancellation for order {} failed on event {}: {}", orderId, envelope.eventId(), e.getMessage());
            }
        }
    }

    public void handleOrderConfirmed(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_Inventory")) {
            return;
        }

        Map<String, Object> payload = parsePayload(envelope.payload());
        String reservationId = (String) payload.get("reservationId");

        // Shift inventory stock from reserved to sold
        if (reservationId != null && !reservationId.isBlank()) {
            try {
                reservationService.markSold(reservationId);
            } catch (Exception e) {
                log.warn("Inventory markSold for reservation {} on OrderConfirmed failed: {}", reservationId, e.getMessage());
            }
        }
    }

    public void handleConfirmOrderCommand(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_ConfirmCommand")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String paymentId = (String) payload.get("paymentId");
        String reservationId = (String) payload.get("reservationId");
        String amountStr = (String) payload.get("amount");
        BigDecimal amount = amountStr != null && !amountStr.isBlank() ? new BigDecimal(amountStr) : null;

        if (orderId != null && !orderId.isBlank()) {
            try {
                orderService.onConfirmOrder(orderId, paymentId, reservationId, amount, null, envelope.eventId());
            } catch (Exception e) {
                log.error("Order confirmation command failed for order {}: {}", orderId, e.getMessage(), e);
            }
        }
    }

    public void handleCancelOrderCommand(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME + "_CancelCommand")) {
            return;
        }
        Map<String, Object> payload = parsePayload(envelope.payload());
        String orderId = (String) payload.getOrDefault("orderId", envelope.orderId());
        String reason = (String) payload.getOrDefault("reason", "Cancelled by Saga");

        if (orderId != null && !orderId.isBlank()) {
            try {
                orderService.onCancelOrder(orderId, reason, envelope.eventId());
            } catch (Exception e) {
                log.warn("Order cancellation command for order {} failed: {}", orderId, e.getMessage());
            }
        }
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse event payload JSON: {}", payloadJson);
            return Map.of();
        }
    }
}

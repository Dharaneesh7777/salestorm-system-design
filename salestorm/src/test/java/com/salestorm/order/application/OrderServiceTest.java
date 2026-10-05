package com.salestorm.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.order.application.dto.CreateOrderCommand;
import com.salestorm.order.application.dto.OrderItemDto;
import com.salestorm.order.application.dto.OrderResult;
import com.salestorm.order.domain.OrderRepository;
import com.salestorm.order.domain.OrderStatus;
import com.salestorm.order.domain.OrderStatusHistoryRepository;
import com.salestorm.order.infrastructure.entity.OrderEntity;
import com.salestorm.order.infrastructure.entity.OrderStatusHistoryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OrderServiceTest {

    private FakeOrderRepository fakeOrderRepo;
    private FakeOrderStatusHistoryRepository fakeHistoryRepo;
    private FakeOutboxRepository fakeOutboxRepo;
    private OrderService orderService;

    @BeforeEach
    void setUp() {
        fakeOrderRepo = new FakeOrderRepository();
        fakeHistoryRepo = new FakeOrderStatusHistoryRepository();
        fakeOutboxRepo = new FakeOutboxRepository();

        orderService = new OrderService(
                fakeOrderRepo,
                fakeHistoryRepo,
                fakeOutboxRepo,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("Verify Out-of-Order ConfirmOrder before CreateOrder creates and confirms order directly")
    void testOutOfOrderConfirmBeforeCreate() {
        String orderId = "ORD-OUT-OF-ORDER-1";
        String paymentId = "PAY-CONFIRM-FIRST";
        String reservationId = "RES-OUT-1";
        BigDecimal amount = new BigDecimal("89.99");
        String eventId = UUID.randomUUID().toString();

        // 1. ConfirmOrder arrives before CreateOrder!
        OrderResult confirmedResult = orderService.onConfirmOrder(
                orderId, paymentId, reservationId, amount, 8001L, eventId
        );

        assertNotNull(confirmedResult);
        assertEquals(orderId, confirmedResult.orderId());
        assertEquals(OrderStatus.CONFIRMED, confirmedResult.status());
        assertEquals(amount, confirmedResult.totalAmount());

        // Verify status history recorded
        OrderEntity entity = fakeOrderRepo.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CONFIRMED, entity.getStatus());
        assertEquals(1, entity.getHistory().size());
        assertEquals("CONFIRMED", entity.getHistory().get(0).getNewStatus());

        // 2. Subsequent CreateOrder arrives later -> should return existing without resetting status to CREATED!
        CreateOrderCommand lateCreate = new CreateOrderCommand(
                orderId,
                8001L,
                reservationId,
                amount,
                "USD",
                List.of(new OrderItemDto(1L, 1, amount, amount))
        );
        OrderResult afterLateCreate = orderService.onCreateOrder(lateCreate, UUID.randomUUID().toString());

        assertEquals(OrderStatus.CONFIRMED, afterLateCreate.status(), "Order status must remain CONFIRMED and not reset");
    }

    @Test
    @DisplayName("Verify Order Creation, Payment Initiation, and Confirmation State History Trail")
    void testFullOrderLifecycleWithAuditHistory() {
        String orderId = "ORD-LIFECYCLE-1";
        String event1 = UUID.randomUUID().toString();
        String event2 = UUID.randomUUID().toString();
        String event3 = UUID.randomUUID().toString();

        // 1. Create Order
        CreateOrderCommand createCmd = new CreateOrderCommand(
                orderId, 9001L, "RES-901", new BigDecimal("120.00"), "USD",
                List.of(new OrderItemDto(1L, 2, new BigDecimal("60.00"), new BigDecimal("120.00")))
        );
        OrderResult created = orderService.onCreateOrder(createCmd, event1);
        assertEquals(OrderStatus.CREATED, created.status());
        assertEquals(1, created.history().size());

        // 2. Payment Initiated
        OrderResult paymentPending = orderService.onPaymentInitiated(orderId, "PAY-901", event2);
        assertEquals(OrderStatus.PAYMENT_PENDING, paymentPending.status());
        assertEquals(2, paymentPending.history().size());

        // 3. Confirm Order
        OrderResult confirmed = orderService.onConfirmOrder(orderId, "PAY-901", "RES-901", new BigDecimal("120.00"), 9001L, event3);
        assertEquals(OrderStatus.CONFIRMED, confirmed.status());
        assertEquals(3, confirmed.history().size());
    }

    @Test
    @DisplayName("Verify Cancellation after SHIPPED is strictly rejected")
    void testCancelRejectedAfterShipped() {
        String orderId = "ORD-SHIPPED-1";
        CreateOrderCommand createCmd = new CreateOrderCommand(
                orderId, 9002L, "RES-902", new BigDecimal("50.00"), "USD", null
        );
        orderService.onCreateOrder(createCmd, "e1");
        orderService.onConfirmOrder(orderId, "PAY-902", "RES-902", new BigDecimal("50.00"), 9002L, "e2");
        orderService.updateOrderStatus(orderId, OrderStatus.SHIPPED, "Package on truck", "e3");

        assertThrows(IllegalStateException.class, () -> {
            orderService.onCancelOrder(orderId, "Late cancel request", "e4");
        });
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private static class FakeOrderRepository implements OrderRepository {
        final List<OrderEntity> savedOrders = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized OrderEntity save(OrderEntity order) {
            savedOrders.removeIf(o -> o.getOrderId().equals(order.getOrderId()));
            savedOrders.add(order);
            return order;
        }

        @Override
        public synchronized Optional<OrderEntity> findById(String orderId) {
            return savedOrders.stream().filter(o -> o.getOrderId().equals(orderId)).findFirst();
        }

        @Override
        public synchronized Optional<OrderEntity> findByReservationId(String reservationId) {
            return savedOrders.stream().filter(o -> reservationId.equals(o.getReservationId())).findFirst();
        }

        @Override
        public synchronized List<OrderEntity> findByCustomerId(Long customerId) {
            return savedOrders.stream().filter(o -> o.getCustomerId().equals(customerId)).toList();
        }

        @Override
        public synchronized List<OrderEntity> findByStatus(OrderStatus status) {
            return savedOrders.stream().filter(o -> o.getStatus() == status).toList();
        }
    }

    private static class FakeOrderStatusHistoryRepository implements OrderStatusHistoryRepository {
        final List<OrderStatusHistoryEntity> historyList = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized OrderStatusHistoryEntity save(OrderStatusHistoryEntity history) {
            historyList.add(history);
            return history;
        }

        @Override
        public synchronized List<OrderStatusHistoryEntity> findByOrderId(String orderId) {
            return historyList.stream().filter(h -> h.getOrder().getOrderId().equals(orderId)).toList();
        }

        @Override
        public synchronized Optional<OrderStatusHistoryEntity> findByOrderIdAndEventId(String orderId, String eventId) {
            return historyList.stream()
                    .filter(h -> h.getOrder().getOrderId().equals(orderId) && eventId.equals(h.getEventId()))
                    .findFirst();
        }
    }

    private static class FakeOutboxRepository implements OutboxRepository {
        final List<OutboxEntity> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized OutboxEntity save(OutboxEntity outboxEntity) {
            events.add(outboxEntity);
            return outboxEntity;
        }

        @Override
        public synchronized Optional<OutboxEntity> findById(String eventId) {
            return events.stream().filter(e -> e.getEventId().equals(eventId)).findFirst();
        }

        @Override
        public synchronized List<OutboxEntity> findUnpublished(int limit) {
            return events.stream().filter(e -> e.getPublishedAt() == null).limit(limit).toList();
        }

        @Override
        public synchronized int markPublished(String eventId, java.time.Instant publishedAt) {
            for (OutboxEntity e : events) {
                if (e.getEventId().equals(eventId)) {
                    e.setPublishedAt(publishedAt);
                    return 1;
                }
            }
            return 0;
        }
    }
}

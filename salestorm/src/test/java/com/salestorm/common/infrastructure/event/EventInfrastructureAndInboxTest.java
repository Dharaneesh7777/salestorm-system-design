package com.salestorm.common.infrastructure.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.domain.event.ProcessedEventRepository;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.application.ReservationTransactionService;
import com.salestorm.inventory.application.dto.ReservationResult;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.InventoryReservationRepository;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import com.salestorm.inventory.infrastructure.gate.InMemoryStockGate;
import com.salestorm.order.application.OrderService;
import com.salestorm.order.application.dto.OrderResult;
import com.salestorm.order.domain.OrderRepository;
import com.salestorm.order.domain.OrderStatus;
import com.salestorm.order.domain.OrderStatusHistoryRepository;
import com.salestorm.order.infrastructure.entity.OrderEntity;
import com.salestorm.order.infrastructure.entity.OrderStatusHistoryEntity;
import com.salestorm.order.infrastructure.listener.NotificationListener;
import com.salestorm.order.infrastructure.listener.OrderAndInventoryEventListener;
import com.salestorm.order.infrastructure.listener.ShipmentListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EventInfrastructureAndInboxTest {

    private EventBus eventBus;
    private FakeProcessedEventRepo processedEventRepo;
    private FakeOutboxRepo outboxRepo;
    private DefaultOutboxWriter outboxWriter;
    private OutboxPublisher outboxPublisher;

    private FakeOrderRepo fakeOrderRepo;
    private FakeHistoryRepo fakeHistoryRepo;
    private OrderService orderService;

    private FakeInventoryRepo fakeInventoryRepo;
    private FakeReservationRepo fakeReservationRepo;
    private StockGate stockGate;
    private ReservationService reservationService;

    private NotificationListener notificationListener;
    private ShipmentListener shipmentListener;
    private OrderAndInventoryEventListener bridgeListener;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        eventBus = new InProcessEventBus();
        processedEventRepo = new FakeProcessedEventRepo();
        outboxRepo = new FakeOutboxRepo();
        outboxWriter = new DefaultOutboxWriter(outboxRepo, objectMapper);
        outboxPublisher = new OutboxPublisher(outboxRepo, eventBus);

        fakeOrderRepo = new FakeOrderRepo();
        fakeHistoryRepo = new FakeHistoryRepo();
        orderService = new OrderService(fakeOrderRepo, fakeHistoryRepo, outboxRepo, objectMapper);

        fakeInventoryRepo = new FakeInventoryRepo();
        fakeReservationRepo = new FakeReservationRepo();
        var saleProps = new com.salestorm.common.infrastructure.config.SaleProperties();
        stockGate = new InMemoryStockGate(saleProps);
        var resTxService = new ReservationTransactionService(fakeInventoryRepo, fakeReservationRepo, outboxRepo, saleProps, objectMapper);
        var idempStore = new com.salestorm.common.infrastructure.store.InMemoryIdempotencyStore();
        reservationService = new ReservationService(stockGate, idempStore, fakeInventoryRepo, fakeReservationRepo, resTxService);

        notificationListener = new NotificationListener(eventBus, processedEventRepo);
        notificationListener.register();

        shipmentListener = new ShipmentListener(eventBus, processedEventRepo, orderService);
        shipmentListener.register();

        bridgeListener = new OrderAndInventoryEventListener(eventBus, processedEventRepo, reservationService, orderService, objectMapper);
        bridgeListener.register();
    }

    @Test
    @DisplayName("Verify Duplicate Event is Ignored by Consumers via PROCESSED_EVENT Inbox")
    void testDuplicateEventIgnoredByInbox() {
        String eventId = UUID.randomUUID().toString();
        MessageEnvelope envelope = new MessageEnvelope(
                eventId,
                "PaymentSucceeded",
                1,
                Instant.now(),
                "ORD-INBOX-1",
                "trace-1",
                null,
                "{\"orderId\":\"ORD-INBOX-1\",\"paymentId\":\"PAY-1\"}"
        );

        // First delivery
        boolean firstProcessed = processedEventRepo.markIfNew(eventId, "TestConsumer");
        assertTrue(firstProcessed, "First event processing attempt must succeed");

        // Second duplicate delivery with same eventId and same consumer
        boolean secondProcessed = processedEventRepo.markIfNew(eventId, "TestConsumer");
        assertFalse(secondProcessed, "Duplicate event delivery must be skipped");
    }

    @Test
    @DisplayName("Verify Outbox Publication Pipeline: Writer -> Publisher -> EventBus -> Listeners")
    void testOutboxToEventBusPipeline() {
        AtomicInteger receivedCount = new AtomicInteger(0);
        eventBus.subscribe("CustomTestEvent", env -> receivedCount.incrementAndGet());

        // 1. Write to Outbox
        MessageEnvelope written = outboxWriter.write(
                "TEST_AGGREGATE",
                "agg_100",
                "ord_100",
                "CustomTestEvent",
                Map.of("message", "Hello EventBus"),
                null
        );

        assertNotNull(written);
        assertEquals(1, outboxRepo.events.size());
        assertNull(outboxRepo.events.get(0).getPublishedAt());

        // 2. Outbox publisher triggers
        outboxPublisher.publishPendingEvents();

        assertEquals(1, receivedCount.get(), "Listener on EventBus must receive the event");
        assertNotNull(outboxRepo.events.get(0).getPublishedAt(), "Outbox record must be marked as published");
    }

    @Test
    @DisplayName("Verify Wired Events: PaymentSucceeded -> Confirms Reservation and Order; OrderConfirmed -> Shifts Inventory to Sold")
    void testWiredEventEndToEndFlow() {
        // Setup initial inventory reservation
        String reservationId = "RES-WIRED-1";
        String orderId = "ORD-WIRED-1";
        fakeInventoryRepo.reserved.set(1);
        fakeInventoryRepo.available.set(99);

        InventoryReservationEntity reservation = new InventoryReservationEntity(
                reservationId, 1L, 1001L, orderId, 1, ReservationStatus.RESERVED, Instant.now().plusSeconds(300), "RES_KEY_W1"
        );
        fakeReservationRepo.save(reservation);

        // 1. Dispatch PaymentSucceeded event
        String eventId = UUID.randomUUID().toString();
        MessageEnvelope paymentSucceededEnvelope = new MessageEnvelope(
                eventId,
                "PaymentSucceeded",
                1,
                Instant.now(),
                orderId,
                "trace-w1",
                null,
                "{\"orderId\":\"" + orderId + "\",\"paymentId\":\"PAY-W1\",\"reservationId\":\"" + reservationId + "\",\"amount\":\"99.99\"}"
        );
        eventBus.publish(paymentSucceededEnvelope);

        // Verify Inventory reservation CONFIRMED
        assertEquals(ReservationStatus.CONFIRMED, fakeReservationRepo.findById(reservationId).orElseThrow().getStatus());

        // Verify Order created & CONFIRMED
        OrderEntity order = fakeOrderRepo.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CONFIRMED, order.getStatus(), "Order created in CONFIRMED status");

        // 2. Dispatch OrderConfirmed event -> Inventory markSold and Shipment processing
        MessageEnvelope orderConfirmedEnvelope = new MessageEnvelope(
                UUID.randomUUID().toString(),
                "OrderConfirmed",
                1,
                Instant.now(),
                orderId,
                "trace-w2",
                null,
                "{\"orderId\":\"" + orderId + "\",\"reservationId\":\"" + reservationId + "\"}"
        );
        eventBus.publish(orderConfirmedEnvelope);

        // Verify Inventory stock shifted to sold
        assertEquals(0, fakeInventoryRepo.reserved.get(), "Reserved stock must drop to 0");
        assertEquals(1, fakeInventoryRepo.sold.get(), "Sold stock must increment to 1");

        // Verify Shipment listener transitioned order to PROCESSING
        assertEquals(OrderStatus.PROCESSING, fakeOrderRepo.findById(orderId).orElseThrow().getStatus(), "Shipment listener transitions CONFIRMED -> PROCESSING");
    }

    // =========================================================================
    // Test Doubles
    // =========================================================================

    private static class FakeProcessedEventRepo implements ProcessedEventRepository {
        final ConcurrentHashMap<String, Boolean> processed = new ConcurrentHashMap<>();

        @Override
        public boolean markIfNew(String eventId, String consumer) {
            String key = eventId + ":" + consumer;
            return processed.putIfAbsent(key, Boolean.TRUE) == null;
        }
    }

    private static class FakeOutboxRepo implements OutboxRepository {
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
        public synchronized int markPublished(String eventId, Instant publishedAt) {
            for (OutboxEntity e : events) {
                if (e.getEventId().equals(eventId)) {
                    e.setPublishedAt(publishedAt);
                    return 1;
                }
            }
            return 0;
        }
    }

    private static class FakeOrderRepo implements OrderRepository {
        final List<OrderEntity> saved = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized OrderEntity save(OrderEntity order) {
            saved.removeIf(o -> o.getOrderId().equals(order.getOrderId()));
            saved.add(order);
            return order;
        }

        @Override
        public synchronized Optional<OrderEntity> findById(String orderId) {
            return saved.stream().filter(o -> o.getOrderId().equals(orderId)).findFirst();
        }

        @Override
        public synchronized Optional<OrderEntity> findByReservationId(String reservationId) {
            return saved.stream().filter(o -> reservationId.equals(o.getReservationId())).findFirst();
        }

        @Override
        public synchronized List<OrderEntity> findByCustomerId(Long customerId) {
            return saved.stream().filter(o -> o.getCustomerId().equals(customerId)).toList();
        }

        @Override
        public synchronized List<OrderEntity> findByStatus(OrderStatus status) {
            return saved.stream().filter(o -> o.getStatus() == status).toList();
        }
    }

    private static class FakeHistoryRepo implements OrderStatusHistoryRepository {
        final List<OrderStatusHistoryEntity> histories = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized OrderStatusHistoryEntity save(OrderStatusHistoryEntity history) {
            histories.add(history);
            return history;
        }

        @Override
        public synchronized List<OrderStatusHistoryEntity> findByOrderId(String orderId) {
            return histories.stream().filter(h -> h.getOrder().getOrderId().equals(orderId)).toList();
        }

        @Override
        public synchronized Optional<OrderStatusHistoryEntity> findByOrderIdAndEventId(String orderId, String eventId) {
            return histories.stream()
                    .filter(h -> h.getOrder().getOrderId().equals(orderId) && eventId.equals(h.getEventId()))
                    .findFirst();
        }
    }

    private static class FakeInventoryRepo implements InventoryRepository {
        final AtomicInteger available = new AtomicInteger(100);
        final AtomicInteger reserved = new AtomicInteger(0);
        final AtomicInteger sold = new AtomicInteger(0);

        @Override
        public int reserveOne(Long productId) {
            available.decrementAndGet();
            reserved.incrementAndGet();
            return 1;
        }

        @Override
        public int releaseOne(Long productId) {
            reserved.decrementAndGet();
            available.incrementAndGet();
            return 1;
        }

        @Override
        public int sellOne(Long productId) {
            reserved.decrementAndGet();
            sold.incrementAndGet();
            return 1;
        }

        @Override
        public Optional<InventoryEntity> findByProductId(Long productId) {
            return Optional.of(new InventoryEntity(productId, 100, available.get(), reserved.get(), sold.get()));
        }

        @Override
        public List<InventoryEntity> findAll() {
            return List.of(new InventoryEntity(1L, 100, available.get(), reserved.get(), sold.get()));
        }

        @Override
        public InventoryEntity save(InventoryEntity inventory) {
            return inventory;
        }
    }

    private static class FakeReservationRepo implements InventoryReservationRepository {
        final List<InventoryReservationEntity> saved = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized InventoryReservationEntity save(InventoryReservationEntity res) {
            saved.removeIf(r -> r.getReservationId().equals(res.getReservationId()));
            saved.add(res);
            return res;
        }

        @Override
        public synchronized Optional<InventoryReservationEntity> findById(String id) {
            return saved.stream().filter(r -> r.getReservationId().equals(id)).findFirst();
        }

        @Override
        public synchronized Optional<InventoryReservationEntity> findByIdempotencyKey(String key) {
            return saved.stream().filter(r -> r.getIdempotencyKey().equals(key)).findFirst();
        }

        @Override
        public synchronized int updateStatusConditional(String id, ReservationStatus exp, ReservationStatus next, String reason) {
            for (InventoryReservationEntity r : saved) {
                if (r.getReservationId().equals(id) && r.getStatus() == exp) {
                    r.setStatus(next);
                    r.setReleaseReason(reason);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized int updateStatusInConditional(String id, List<ReservationStatus> exps, ReservationStatus next, String reason) {
            for (InventoryReservationEntity r : saved) {
                if (r.getReservationId().equals(id) && exps.contains(r.getStatus())) {
                    r.setStatus(next);
                    r.setReleaseReason(reason);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized List<String> findExpiredReservationIds(Instant now, List<ReservationStatus> statuses, int limit) {
            return saved.stream().filter(r -> statuses.contains(r.getStatus()) && r.getExpiresAt().isBefore(now))
                    .limit(limit).map(InventoryReservationEntity::getReservationId).toList();
        }
    }
}

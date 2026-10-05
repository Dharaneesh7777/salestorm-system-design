package com.salestorm.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.IdempotencyStore;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.config.SaleProperties;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.common.infrastructure.store.InMemoryIdempotencyStore;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.application.ReservationTransactionService;
import com.salestorm.inventory.application.dto.ReservationResult;
import com.salestorm.inventory.domain.DuplicateRequestConflictException;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.InventoryReservationRepository;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import com.salestorm.inventory.infrastructure.gate.InMemoryStockGate;
import com.salestorm.payment.application.dto.PaymentResult;
import com.salestorm.payment.application.dto.RefundResult;
import com.salestorm.payment.domain.PaymentRepository;
import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.domain.RefundRepository;
import com.salestorm.payment.infrastructure.adapter.MockGatewayAdapter;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import com.salestorm.payment.infrastructure.entity.RefundEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PaymentServiceTest {

    private MockGatewayAdapter mockGateway;
    private FakePaymentRepository fakePaymentRepo;
    private FakeRefundRepository fakeRefundRepo;
    private FakeOutboxRepository fakeOutboxRepo;
    private FakeInventoryReservationRepo fakeReservationRepo;
    private FakeInventoryRepo fakeInventoryRepo;
    private StockGate stockGate;
    private IdempotencyStore idempotencyStore;
    private SaleProperties saleProperties;
    private ReservationService reservationService;
    private PaymentTransactionService transactionService;
    private PaymentService paymentService;
    private PaymentReconciler paymentReconciler;

    @BeforeEach
    void setUp() {
        mockGateway = new CountingMockGatewayAdapter();
        fakePaymentRepo = new FakePaymentRepository();
        fakeRefundRepo = new FakeRefundRepository();
        fakeOutboxRepo = new FakeOutboxRepository();
        fakeReservationRepo = new FakeInventoryReservationRepo();
        fakeInventoryRepo = new FakeInventoryRepo();
        idempotencyStore = new InMemoryIdempotencyStore();

        saleProperties = new SaleProperties();
        saleProperties.setPaymentGraceSeconds(600L);
        saleProperties.setReservationTtlSeconds(300L);
        stockGate = new InMemoryStockGate(saleProperties);

        ObjectMapper objectMapper = new ObjectMapper();

        ReservationTransactionService resTxService = new ReservationTransactionService(
                fakeInventoryRepo,
                fakeReservationRepo,
                fakeOutboxRepo,
                saleProperties,
                objectMapper
        );

        reservationService = new ReservationService(
                stockGate,
                idempotencyStore,
                fakeInventoryRepo,
                fakeReservationRepo,
                resTxService
        );

        transactionService = new PaymentTransactionService(
                fakePaymentRepo,
                fakeRefundRepo,
                fakeOutboxRepo,
                objectMapper
        );

        paymentService = new PaymentService(
                mockGateway,
                fakePaymentRepo,
                transactionService,
                idempotencyStore,
                reservationService
        );

        paymentReconciler = new PaymentReconciler(
                fakePaymentRepo,
                mockGateway,
                transactionService,
                reservationService,
                saleProperties
        );
    }

    @Test
    @DisplayName("Verify Duplicate Pay Click = Exactly One Charge: Concurrent or sequential requests execute 1 gateway charge")
    void testDuplicatePayClickResultsInOneCharge() throws Exception {
        String reservationId = UUID.randomUUID().toString();
        String idempotencyKey = "IDEMP-DUPL-CLICK";
        BigDecimal amount = new BigDecimal("99.99");
        mockGateway.setForceSuccess(true);

        CountingMockGatewayAdapter countingGateway = (CountingMockGatewayAdapter) mockGateway;

        // Simulate 2 concurrent clicks from user
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        List<PaymentResult> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    PaymentResult res = paymentService.pay(reservationId, amount, idempotencyKey);
                    results.add(res);
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertTrue(exceptions.isEmpty(), "No exceptions should occur during duplicate payment clicks");
        assertEquals(2, results.size());

        // Verify both returned the same payment ID
        PaymentResult res1 = results.get(0);
        PaymentResult res2 = results.get(1);
        assertEquals(res1.paymentId(), res2.paymentId());
        assertEquals(PaymentStatus.SUCCEEDED, res1.status());
        assertEquals(PaymentStatus.SUCCEEDED, res2.status());

        // Verify Gateway charge was executed exactly ONCE
        assertEquals(1, countingGateway.chargeCount.get(), "Gateway charge must be called exactly ONCE");
        assertEquals(1, fakePaymentRepo.savedPayments.size(), "Only 1 PaymentEntity must exist in database");
    }

    @Test
    @DisplayName("Verify Same Idempotency Key with Different Payload throws 409 Conflict")
    void testSameIdempotencyKeyDifferentPayloadThrowsConflict() {
        String idempKey = "CONFLICT-KEY-PAY";
        mockGateway.setForceSuccess(true);

        // First call
        PaymentResult res1 = paymentService.pay("res_101", new BigDecimal("50.00"), idempKey);
        assertNotNull(res1);

        // Second call with different amount
        assertThrows(DuplicateRequestConflictException.class, () -> {
            paymentService.pay("res_101", new BigDecimal("100.00"), idempKey);
        });
    }

    @Test
    @DisplayName("Verify Successful Payment Flow: P1 -> Gateway (Success) -> P2 (SUCCEEDED) and confirms reservation")
    void testSuccessfulPaymentFlow() {
        String reservationId = UUID.randomUUID().toString();
        InventoryReservationEntity reservation = new InventoryReservationEntity(
                reservationId, 1L, 1001L, null, 1, ReservationStatus.RESERVED, Instant.now().plusSeconds(300), "RES_KEY_1"
        );
        fakeReservationRepo.save(reservation);

        mockGateway.setForceSuccess(true);

        PaymentResult result = paymentService.pay(reservationId, new BigDecimal("150.00"), "PAY-SUCCESS-KEY");

        assertEquals(PaymentStatus.SUCCEEDED, result.status());
        assertNotNull(result.gatewayRef());

        // Verify reservation confirmed
        assertEquals(ReservationStatus.CONFIRMED, fakeReservationRepo.findById(reservationId).orElseThrow().getStatus());

        // Verify Outbox events recorded (PaymentInitiated + PaymentSucceeded)
        boolean hasSucceededEvent = fakeOutboxRepo.savedEvents.stream()
                .anyMatch(e -> "PaymentSucceeded".equals(e.getType()));
        assertTrue(hasSucceededEvent, "PaymentSucceeded Outbox event must be recorded");
    }

    @Test
    @DisplayName("Verify Declined Payment Flow: P1 -> Gateway (Declined) -> P2 (FAILED) and releases reservation")
    void testDeclinedPaymentFlow() {
        String reservationId = UUID.randomUUID().toString();
        InventoryReservationEntity reservation = new InventoryReservationEntity(
                reservationId, 1L, 1002L, null, 1, ReservationStatus.RESERVED, Instant.now().plusSeconds(300), "RES_KEY_2"
        );
        fakeReservationRepo.save(reservation);
        fakeInventoryRepo.reserved.set(1);
        fakeInventoryRepo.available.set(99);

        mockGateway.setForceDecline(true);

        PaymentResult result = paymentService.pay(reservationId, new BigDecimal("200.00"), "PAY-DECLINE-KEY");

        assertEquals(PaymentStatus.FAILED, result.status());

        // Verify reservation released with PAYMENT_FAILED
        InventoryReservationEntity updatedRes = fakeReservationRepo.findById(reservationId).orElseThrow();
        assertEquals(ReservationStatus.RELEASED, updatedRes.getStatus());
        assertEquals("PAYMENT_FAILED", updatedRes.getReleaseReason());

        boolean hasFailedEvent = fakeOutboxRepo.savedEvents.stream()
                .anyMatch(e -> "PaymentFailed".equals(e.getType()));
        assertTrue(hasFailedEvent, "PaymentFailed Outbox event must be recorded");
    }

    @Test
    @DisplayName("Verify Timeout Payment Flow: Gateway timeout updates P2 to TIMEOUT")
    void testTimeoutPaymentFlow() {
        mockGateway.setForceTimeout(true);

        PaymentResult result = paymentService.pay("res_303", new BigDecimal("80.00"), "PAY-TIMEOUT-KEY");

        assertEquals(PaymentStatus.TIMEOUT, result.status());
        PaymentEntity entity = fakePaymentRepo.findById(result.paymentId()).orElseThrow();
        assertEquals(PaymentStatus.TIMEOUT, entity.getStatus());
    }

    @Test
    @DisplayName("Verify Refund Flow: Exactly once per payment")
    void testRefundExactlyOnce() {
        // Create succeeded payment
        mockGateway.setForceSuccess(true);
        PaymentResult payResult = paymentService.pay("res_404", new BigDecimal("120.00"), "PAY-FOR-REFUND");
        assertEquals(PaymentStatus.SUCCEEDED, payResult.status());

        // First refund call
        RefundResult refundResult = paymentService.refund(payResult.paymentId(), new BigDecimal("120.00"), "User return");
        assertEquals("REFUNDED", refundResult.status());

        // Verify payment entity marked REFUNDED
        assertEquals(PaymentStatus.REFUNDED, fakePaymentRepo.findById(payResult.paymentId()).orElseThrow().getStatus());

        // Second refund call on the same payment must fail
        assertThrows(IllegalStateException.class, () -> {
            paymentService.refund(payResult.paymentId(), new BigDecimal("120.00"), "Duplicate refund attempt");
        });
    }

    @Test
    @DisplayName("Verify PaymentReconciler resolves TIMEOUT payment to SUCCEEDED when gateway status is SUCCEEDED")
    void testPaymentReconcilerResolvesTimeout() {
        // Create payment in TIMEOUT status
        PaymentEntity timeoutPayment = new PaymentEntity(
                "pay_timeout_1",
                "ord_timeout_1",
                "res_timeout_1",
                new BigDecimal("50.00"),
                "USD",
                PaymentStatus.TIMEOUT,
                "IDEMP_RECON_1",
                "hash1"
        );
        fakePaymentRepo.save(timeoutPayment);

        // Gateway records SUCCEEDED on query
        mockGateway.recordDirectStatus("pay_timeout_1", com.salestorm.payment.domain.GatewayResponse.success("gw_reconciled_123"));

        // Run reconciler
        paymentReconciler.reconcileTimeoutPayments();

        PaymentEntity reconciled = fakePaymentRepo.findById("pay_timeout_1").orElseThrow();
        assertEquals(PaymentStatus.SUCCEEDED, reconciled.getStatus());
        assertEquals("gw_reconciled_123", reconciled.getGatewayRef());
    }

    // =========================================================================
    // Test Doubles / Fakes
    // =========================================================================

    private static class CountingMockGatewayAdapter extends MockGatewayAdapter {
        final AtomicInteger chargeCount = new AtomicInteger(0);

        @Override
        public com.salestorm.payment.domain.GatewayResponse charge(com.salestorm.payment.domain.PaymentChargeRequest request) {
            chargeCount.incrementAndGet();
            return super.charge(request);
        }
    }

    private static class FakePaymentRepository implements PaymentRepository {
        final List<PaymentEntity> savedPayments = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized PaymentEntity save(PaymentEntity payment) {
            for (PaymentEntity p : savedPayments) {
                if (p.getIdempotencyKey().equals(payment.getIdempotencyKey()) && !p.getPaymentId().equals(payment.getPaymentId())) {
                    throw new org.springframework.dao.DataIntegrityViolationException("Duplicate idempotency key: " + payment.getIdempotencyKey());
                }
            }
            savedPayments.removeIf(p -> p.getPaymentId().equals(payment.getPaymentId()));
            savedPayments.add(payment);
            return payment;
        }

        @Override
        public synchronized Optional<PaymentEntity> findById(String paymentId) {
            return savedPayments.stream().filter(p -> p.getPaymentId().equals(paymentId)).findFirst();
        }

        @Override
        public synchronized Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey) {
            return savedPayments.stream().filter(p -> p.getIdempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public synchronized Optional<PaymentEntity> findByReservationId(String reservationId) {
            return savedPayments.stream().filter(p -> reservationId.equals(p.getReservationId())).findFirst();
        }

        @Override
        public synchronized List<PaymentEntity> findByStatus(PaymentStatus status) {
            return savedPayments.stream().filter(p -> p.getStatus() == status).toList();
        }

        @Override
        public synchronized List<PaymentEntity> findTimeoutPayments(Instant beforeTime, int limit) {
            return savedPayments.stream().filter(p -> p.getStatus() == PaymentStatus.TIMEOUT).limit(limit).toList();
        }

        @Override
        public synchronized int updateStatusConditional(String paymentId, PaymentStatus expectedStatus, PaymentStatus newStatus, String gatewayRef) {
            for (PaymentEntity p : savedPayments) {
                if (p.getPaymentId().equals(paymentId) && p.getStatus() == expectedStatus) {
                    p.setStatus(newStatus);
                    if (gatewayRef != null) p.setGatewayRef(gatewayRef);
                    return 1;
                }
            }
            return 0;
        }
    }

    private static class FakeRefundRepository implements RefundRepository {
        final List<RefundEntity> savedRefunds = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized RefundEntity save(RefundEntity refund) {
            savedRefunds.removeIf(r -> r.getRefundId().equals(refund.getRefundId()));
            savedRefunds.add(refund);
            return refund;
        }

        @Override
        public synchronized Optional<RefundEntity> findById(String refundId) {
            return savedRefunds.stream().filter(r -> r.getRefundId().equals(refundId)).findFirst();
        }

        @Override
        public synchronized Optional<RefundEntity> findByPaymentId(String paymentId) {
            return savedRefunds.stream().filter(r -> r.getPaymentId().equals(paymentId)).findFirst();
        }

        @Override
        public synchronized int updateStatusConditional(String refundId, String expectedStatus, String newStatus) {
            for (RefundEntity r : savedRefunds) {
                if (r.getRefundId().equals(refundId) && r.getStatus().equals(expectedStatus)) {
                    r.setStatus(newStatus);
                    return 1;
                }
            }
            return 0;
        }
    }

    private static class FakeOutboxRepository implements OutboxRepository {
        final List<OutboxEntity> savedEvents = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized OutboxEntity save(OutboxEntity outboxEntity) {
            savedEvents.add(outboxEntity);
            return outboxEntity;
        }

        @Override
        public synchronized Optional<OutboxEntity> findById(String eventId) {
            return savedEvents.stream().filter(e -> e.getEventId().equals(eventId)).findFirst();
        }

        @Override
        public synchronized List<OutboxEntity> findUnpublished(int limit) {
            return savedEvents.stream()
                    .filter(e -> e.getPublishedAt() == null)
                    .limit(limit)
                    .toList();
        }

        @Override
        public synchronized int markPublished(String eventId, Instant publishedAt) {
            for (OutboxEntity e : savedEvents) {
                if (e.getEventId().equals(eventId)) {
                    e.setPublishedAt(publishedAt);
                    return 1;
                }
            }
            return 0;
        }
    }

    private static class FakeInventoryReservationRepo implements InventoryReservationRepository {
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
}

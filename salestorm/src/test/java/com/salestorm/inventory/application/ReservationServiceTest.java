package com.salestorm.inventory.application;

import com.salestorm.common.domain.IdempotencyRecord;
import com.salestorm.common.domain.IdempotencyStore;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.config.SaleProperties;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.common.infrastructure.store.InMemoryIdempotencyStore;
import com.salestorm.inventory.application.dto.ReservationResult;
import com.salestorm.inventory.domain.DuplicateRequestConflictException;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.InventoryReservationRepository;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.domain.StockDepletedException;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.domain.TemporarilyUnavailableException;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import com.salestorm.inventory.infrastructure.gate.InMemoryStockGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReservationServiceTest {

    private StockGate stockGate;
    private IdempotencyStore idempotencyStore;
    private FakeInventoryRepository fakeInventoryRepo;
    private FakeReservationRepository fakeReservationRepo;
    private FakeOutboxRepository fakeOutboxRepo;
    private SaleProperties saleProperties;
    private ReservationTransactionService transactionService;
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        saleProperties = new SaleProperties();
        saleProperties.setTotalStock(100);
        saleProperties.setReservationTtlSeconds(300L);

        stockGate = new InMemoryStockGate(saleProperties);
        idempotencyStore = new InMemoryIdempotencyStore();
        fakeInventoryRepo = new FakeInventoryRepository();
        fakeReservationRepo = new FakeReservationRepository();
        fakeOutboxRepo = new FakeOutboxRepository();

        transactionService = new ReservationTransactionService(
                fakeInventoryRepo,
                fakeReservationRepo,
                fakeOutboxRepo,
                saleProperties,
                new ObjectMapper()
        );

        reservationService = new ReservationService(
                stockGate,
                idempotencyStore,
                fakeInventoryRepo,
                fakeReservationRepo,
                transactionService
        );
    }

    @Test
    @DisplayName("Verify Last Item Scenario: 2 concurrent threads compete for 1 unit, exactly 1 wins and 1 is rejected")
    void testLastItemContentionWithTwoThreads() throws Exception {
        Long productId = 1L;
        // Set stock to exactly 1 unit in both gate and DB
        stockGate.reset(productId, 1);
        fakeInventoryRepo.save(new InventoryEntity(productId, 1, 1, 0, 0));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        List<ReservationResult> successfulResults = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        Runnable task1 = () -> {
            try {
                startLatch.await();
                ReservationResult res = reservationService.reserve(101L, productId, "KEY-THREAD-1");
                successfulResults.add(res);
            } catch (Throwable t) {
                exceptions.add(t);
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable task2 = () -> {
            try {
                startLatch.await();
                ReservationResult res = reservationService.reserve(102L, productId, "KEY-THREAD-2");
                successfulResults.add(res);
            } catch (Throwable t) {
                exceptions.add(t);
            } finally {
                doneLatch.countDown();
            }
        };

        executor.submit(task1);
        executor.submit(task2);

        // Unleash both threads simultaneously
        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(1, successfulResults.size(), "Exactly 1 thread must succeed");
        assertEquals(1, exceptions.size(), "Exactly 1 thread must fail");
        assertInstanceOf(TemporarilyUnavailableException.class, exceptions.get(0), "Rejected thread gets TemporarilyUnavailableException");
        assertEquals(0, stockGate.getAvailable(productId), "Gate counter should be 0");
        assertEquals(1, fakeReservationRepo.savedReservations.size(), "Exactly 1 reservation persisted");
    }

    @Test
    @DisplayName("Verify duplicate idempotency key with same payload returns 200 OK result without consuming extra stock")
    void testDuplicateKeySamePayload() {
        Long productId = 1L;
        Long customerId = 201L;
        String idempKey = "IDEMP-DUPL-SAME";

        stockGate.reset(productId, 10);
        fakeInventoryRepo.save(new InventoryEntity(productId, 10, 10, 0, 0));

        // First call
        ReservationResult firstResult = reservationService.reserve(customerId, productId, idempKey);
        assertNotNull(firstResult.reservationId());
        assertFalse(firstResult.isDuplicate());
        assertEquals(9, stockGate.getAvailable(productId));

        // Second call with same idempotency key and same payload
        ReservationResult secondResult = reservationService.reserve(customerId, productId, idempKey);
        assertEquals(firstResult.reservationId(), secondResult.reservationId());
        assertTrue(secondResult.isDuplicate());
        assertEquals(9, stockGate.getAvailable(productId), "Stock must NOT be decremented again on duplicate request");
    }

    @Test
    @DisplayName("Verify duplicate idempotency key with different payload throws 409 Conflict")
    void testDuplicateKeyDifferentPayloadThrowsConflict() {
        Long productId = 1L;
        Long customerId1 = 301L;
        Long customerId2 = 302L;
        String idempKey = "IDEMP-CONFLICT-KEY";

        stockGate.reset(productId, 10);
        fakeInventoryRepo.save(new InventoryEntity(productId, 10, 10, 0, 0));

        // First call with customer 301
        ReservationResult first = reservationService.reserve(customerId1, productId, idempKey);
        assertNotNull(first);

        // Second call with same key but different customer ID (302)
        assertThrows(DuplicateRequestConflictException.class, () -> {
            reservationService.reserve(customerId2, productId, idempKey);
        });
    }

    @Test
    @DisplayName("Verify database failure compensates the StockGate counter back to initial value")
    void testDatabaseFailureCompensatesGate() {
        Long productId = 1L;
        stockGate.reset(productId, 5);

        // Fake repo simulates 0 rows updated (DB failure / race condition)
        fakeInventoryRepo.simulateReserveFailure = true;

        assertThrows(StockDepletedException.class, () -> {
            reservationService.reserve(401L, productId, "FAIL-KEY-1");
        });

        // Gate counter was acquired (5 -> 4), but compensated (+1) back to 5
        assertEquals(5, stockGate.getAvailable(productId), "Gate counter must be compensated back to 5 after DB failure");
    }

    @Test
    @DisplayName("Verify Expiry Returns Unit: Expired hold release increments DB available and gate counter")
    void testExpiryReturnsUnit() {
        Long productId = 1L;
        stockGate.reset(productId, 10);
        fakeInventoryRepo.save(new InventoryEntity(productId, 10, 10, 0, 0));

        // Create a reservation: available=9, reserved=1, gate=9
        ReservationResult res = reservationService.reserve(501L, productId, "KEY-EXPIRY-1");
        assertEquals(9, stockGate.getAvailable(productId));
        assertEquals(9, fakeInventoryRepo.available.get());
        assertEquals(1, fakeInventoryRepo.reserved.get());

        // Release with EXPIRED reason
        boolean released = reservationService.release(res.reservationId(), com.salestorm.inventory.domain.ReleaseReason.EXPIRED);
        assertTrue(released, "Release should succeed");

        // Verify DB stock returned and gate counter incremented back to 10
        assertEquals(10, fakeInventoryRepo.available.get(), "Available DB stock should return to 10");
        assertEquals(0, fakeInventoryRepo.reserved.get(), "Reserved DB stock should return to 0");
        assertEquals(10, stockGate.getAvailable(productId), "Gate counter should return to 10");

        // Verify entity status
        InventoryReservationEntity entity = fakeReservationRepo.findById(res.reservationId()).orElseThrow();
        assertEquals(ReservationStatus.RELEASED, entity.getStatus());
        assertEquals("EXPIRED", entity.getReleaseReason());

        // Verify idempotent second release call
        boolean doubleRelease = reservationService.release(res.reservationId(), com.salestorm.inventory.domain.ReleaseReason.EXPIRED);
        assertFalse(doubleRelease, "Subsequent release call must return false");
        assertEquals(10, stockGate.getAvailable(productId), "Gate counter must NOT be incremented again");
    }

    @Test
    @DisplayName("Verify CONFIRMED is Never Expired: Confirmed reservation cannot be released by expiry worker")
    void testConfirmedIsNeverExpired() {
        Long productId = 1L;
        stockGate.reset(productId, 10);
        fakeInventoryRepo.save(new InventoryEntity(productId, 10, 10, 0, 0));

        // Create reservation and transition to CONFIRMED
        ReservationResult res = reservationService.reserve(601L, productId, "KEY-CONFIRM-1");
        ReservationResult confirmed = reservationService.confirm(res.reservationId());
        assertEquals(ReservationStatus.CONFIRMED, confirmed.status());

        // Force expiration timestamp to past
        InventoryReservationEntity entity = fakeReservationRepo.findById(res.reservationId()).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(60));

        // Expiry scheduler tries to release with EXPIRED reason
        boolean released = reservationService.release(res.reservationId(), com.salestorm.inventory.domain.ReleaseReason.EXPIRED);
        assertFalse(released, "Confirmed reservation must NOT be released by expiry worker");

        // Verify status remains CONFIRMED and stock is unchanged
        InventoryReservationEntity afterAttempt = fakeReservationRepo.findById(res.reservationId()).orElseThrow();
        assertEquals(ReservationStatus.CONFIRMED, afterAttempt.getStatus());
        assertEquals(9, fakeInventoryRepo.available.get());
        assertEquals(1, fakeInventoryRepo.reserved.get());
        assertEquals(9, stockGate.getAvailable(productId));
    }

    @Test
    @DisplayName("Verify Two Expiry Workers Cannot Double Release: Concurrent release results in exactly 1 unit released")
    void testTwoExpiryWorkersCannotDoubleRelease() throws Exception {
        Long productId = 1L;
        stockGate.reset(productId, 10);
        fakeInventoryRepo.save(new InventoryEntity(productId, 10, 10, 0, 0));

        ReservationResult res = reservationService.reserve(701L, productId, "KEY-CONCURRENT-EXPIRY");
        assertEquals(9, stockGate.getAvailable(productId));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        List<Boolean> releaseResults = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    boolean outcome = reservationService.release(res.reservationId(), com.salestorm.inventory.domain.ReleaseReason.EXPIRED);
                    releaseResults.add(outcome);
                } catch (Exception e) {
                    // ignore
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        long successCount = releaseResults.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, successCount, "Exactly 1 worker must succeed in releasing the reservation");
        assertEquals(10, fakeInventoryRepo.available.get(), "Available DB stock should be incremented exactly once");
        assertEquals(10, stockGate.getAvailable(productId), "Gate counter should be incremented exactly once");
    }

    @Test
    @DisplayName("Verify markSold transitions CONFIRMED to SOLD and moves stock from reserved to sold")
    void testMarkSold() {
        Long productId = 1L;
        stockGate.reset(productId, 10);
        fakeInventoryRepo.save(new InventoryEntity(productId, 10, 10, 0, 0));

        ReservationResult res = reservationService.reserve(801L, productId, "KEY-SOLD-1");
        reservationService.confirm(res.reservationId());

        boolean sold = reservationService.markSold(res.reservationId());
        assertTrue(sold);

        InventoryReservationEntity entity = fakeReservationRepo.findById(res.reservationId()).orElseThrow();
        assertEquals(ReservationStatus.SOLD, entity.getStatus());
        assertEquals(0, fakeInventoryRepo.reserved.get());
        assertEquals(1, fakeInventoryRepo.sold.get());

        // Idempotent retry
        boolean secondSold = reservationService.markSold(res.reservationId());
        assertTrue(secondSold);
        assertEquals(1, fakeInventoryRepo.sold.get());
    }

    @Test
    @DisplayName("Verify StockReconciler sets gate counter to MySQL available if gate is higher, but never raises above DB")
    void testStockReconciler() {
        Long productId = 1L;
        fakeInventoryRepo.save(new InventoryEntity(productId, 100, 50, 0, 50));
        StockReconciler reconciler = new StockReconciler(fakeInventoryRepo, stockGate);

        // Scenario 1: Gate counter is 60, DB is 50 -> should be lowered to 50
        stockGate.reset(productId, 60);
        reconciler.reconcileStock();
        assertEquals(50, stockGate.getAvailable(productId), "Gate counter should be lowered to DB available count");

        // Scenario 2: Gate counter is 40, DB is 50 -> should NOT be raised (never raise above DB)
        stockGate.reset(productId, 40);
        reconciler.reconcileStock();
        assertEquals(40, stockGate.getAvailable(productId), "Gate counter must NOT be raised when lower than DB");
    }

    // =========================================================================
    // Fakes for isolated unit testing
    // =========================================================================

    private static class FakeInventoryRepository implements InventoryRepository {
        boolean simulateReserveFailure = false;
        final AtomicInteger available = new AtomicInteger(100);
        final AtomicInteger reserved = new AtomicInteger(0);
        final AtomicInteger sold = new AtomicInteger(0);

        @Override
        public int reserveOne(Long productId) {
            if (simulateReserveFailure) {
                return 0;
            }
            while (true) {
                int curr = available.get();
                if (curr <= 0) return 0;
                if (available.compareAndSet(curr, curr - 1)) {
                    reserved.incrementAndGet();
                    return 1;
                }
            }
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
            InventoryEntity inv = new InventoryEntity(productId, 100, available.get(), reserved.get(), sold.get());
            return Optional.of(inv);
        }

        @Override
        public List<InventoryEntity> findAll() {
            InventoryEntity inv = new InventoryEntity(1L, 100, available.get(), reserved.get(), sold.get());
            return List.of(inv);
        }

        @Override
        public InventoryEntity save(InventoryEntity inventory) {
            available.set(inventory.getAvailableQuantity());
            reserved.set(inventory.getReservedQuantity());
            sold.set(inventory.getSoldQuantity());
            return inventory;
        }
    }

    private static class FakeReservationRepository implements InventoryReservationRepository {
        final List<InventoryReservationEntity> savedReservations = Collections.synchronizedList(new ArrayList<>());

        @Override
        public InventoryReservationEntity save(InventoryReservationEntity reservation) {
            savedReservations.add(reservation);
            return reservation;
        }

        @Override
        public Optional<InventoryReservationEntity> findById(String reservationId) {
            return savedReservations.stream()
                    .filter(r -> r.getReservationId().equals(reservationId))
                    .findFirst();
        }

        @Override
        public Optional<InventoryReservationEntity> findByIdempotencyKey(String idempotencyKey) {
            return savedReservations.stream()
                    .filter(r -> r.getIdempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public synchronized int updateStatusConditional(String reservationId, ReservationStatus expectedStatus, ReservationStatus newStatus, String releaseReason) {
            for (InventoryReservationEntity r : savedReservations) {
                if (r.getReservationId().equals(reservationId) && r.getStatus() == expectedStatus) {
                    r.setStatus(newStatus);
                    r.setReleaseReason(releaseReason);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized int updateStatusInConditional(String reservationId, List<ReservationStatus> expectedStatuses, ReservationStatus newStatus, String releaseReason) {
            for (InventoryReservationEntity r : savedReservations) {
                if (r.getReservationId().equals(reservationId) && expectedStatuses.contains(r.getStatus())) {
                    r.setStatus(newStatus);
                    r.setReleaseReason(releaseReason);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized List<String> findExpiredReservationIds(Instant now, List<ReservationStatus> statuses, int limit) {
            return savedReservations.stream()
                    .filter(r -> statuses.contains(r.getStatus()) && r.getExpiresAt().isBefore(now))
                    .limit(limit)
                    .map(InventoryReservationEntity::getReservationId)
                    .toList();
        }
    }

    private static class FakeOutboxRepository implements OutboxRepository {
        final List<OutboxEntity> savedEvents = Collections.synchronizedList(new ArrayList<>());

        @Override
        public OutboxEntity save(OutboxEntity outboxEntity) {
            savedEvents.add(outboxEntity);
            return outboxEntity;
        }

        @Override
        public Optional<OutboxEntity> findById(String eventId) {
            return savedEvents.stream()
                    .filter(e -> e.getEventId().equals(eventId))
                    .findFirst();
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
}

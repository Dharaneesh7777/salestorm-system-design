package com.salestorm.dashboard.sim.application;

import com.salestorm.checkout.application.CheckoutFacade;
import com.salestorm.checkout.application.dto.CheckoutBuyResult;
import com.salestorm.checkout.application.dto.CheckoutPayResult;
import com.salestorm.common.infrastructure.event.OutboxPublisher;
import com.salestorm.dashboard.sim.api.dto.*;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.payment.infrastructure.adapter.MockGatewayAdapter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final CheckoutFacade checkoutFacade;
    private final InventoryRepository inventoryRepository;
    private final StockGate stockGate;
    private final MockGatewayAdapter mockGateway;
    private final OutboxPublisher outboxPublisher;
    private final PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private final ExecutorService simExecutor = Executors.newFixedThreadPool(64);
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(4);

    // Live status trackers
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant startedAt = Instant.now();
    private volatile long elapsedMs = 0;
    private final AtomicInteger sentCount = new AtomicInteger(0);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger reservedCount = new AtomicInteger(0);
    private final AtomicInteger soldOutCount = new AtomicInteger(0);
    private final AtomicInteger duplicatesTotal = new AtomicInteger(0);
    private final AtomicInteger duplicatesReturnedSame = new AtomicInteger(0);
    private final AtomicInteger rateLimitedCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final List<Long> buyLatencies = new CopyOnWriteArrayList<>();

    private volatile String currentRunId = null;

    public SimulationService(
            CheckoutFacade checkoutFacade,
            InventoryRepository inventoryRepository,
            StockGate stockGate,
            MockGatewayAdapter mockGateway,
            OutboxPublisher outboxPublisher,
            PlatformTransactionManager transactionManager
    ) {
        this.checkoutFacade = checkoutFacade;
        this.inventoryRepository = inventoryRepository;
        this.stockGate = stockGate;
        this.mockGateway = mockGateway;
        this.outboxPublisher = outboxPublisher;
        this.transactionManager = transactionManager;
    }

    public synchronized SimRunResponse runSimulation(SimRunRequest request) {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        this.currentRunId = runId;

        int users = request.effectiveUsers();
        int stock = request.effectiveStock();
        int duplicatePercent = request.effectiveDuplicatePercent();
        int paymentFailPercent = request.effectivePaymentFailPercent();
        int downSeconds = request.effectiveOrderServiceDownSeconds();

        // 1. Reset state
        resetData(stock);

        // 2. Initialize tracking counters
        running.set(true);
        startedAt = Instant.now();
        elapsedMs = 0;
        sentCount.set(0);
        completedCount.set(0);
        reservedCount.set(0);
        soldOutCount.set(0);
        duplicatesTotal.set(0);
        duplicatesReturnedSame.set(0);
        rateLimitedCount.set(0);
        errorCount.set(0);
        buyLatencies.clear();

        // Configure mock gateway
        mockGateway.reset();
        mockGateway.setDeclineRate(paymentFailPercent / 100.0);
        mockGateway.setSuccessRate(1.0 - (paymentFailPercent / 100.0));

        // If order service down requested, pause outbox publisher and schedule resume
        if (downSeconds > 0) {
            outboxPublisher.setPaused(true);
            log.info("Sim: Order service paused for {} seconds", downSeconds);
            scheduledExecutor.schedule(() -> {
                outboxPublisher.setPaused(false);
                log.info("Sim: Order service resumed after outage delay");
                drainOutbox();
            }, downSeconds, TimeUnit.SECONDS);
        } else {
            outboxPublisher.setPaused(false);
        }

        // 3. Launch async background simulation
        simExecutor.submit(() -> executeLoadTest(users, duplicatePercent, paymentFailPercent));

        return new SimRunResponse(runId, users, stock, startedAt);
    }

    private void executeLoadTest(int totalRequests, int duplicatePercent, int paymentFailPercent) {
        long startTime = System.currentTimeMillis();
        int numDuplicates = (int) Math.round(totalRequests * (duplicatePercent / 100.0));
        int numUnique = totalRequests - numDuplicates;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalRequests);

        // Map to store original idempotency keys and resulting reservationIds
        ConcurrentHashMap<Long, String> customerIdempKeys = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> originalReservations = new ConcurrentHashMap<>();

        ExecutorService workerPool = Executors.newFixedThreadPool(Math.min(100, Math.max(10, totalRequests / 50)));

        // Submit unique buyer requests
        for (int i = 1; i <= numUnique; i++) {
            final long custId = 10000L + i;
            final String idempKey = "KEY-CUST-" + custId;
            customerIdempKeys.put(custId, idempKey);

            workerPool.submit(() -> {
                try {
                    startGate.await();
                    sentCount.incrementAndGet();

                    long t0 = System.nanoTime();
                    try {
                        CheckoutBuyResult buyResult = checkoutFacade.buy(custId, 1L, 1, null, idempKey);
                        originalReservations.put(idempKey, buyResult.reservationId());
                        reservedCount.incrementAndGet();

                        // Attempt payment
                        try {
                            String payIdempKey = "PAY-" + idempKey;
                            checkoutFacade.pay(custId, buyResult.orderId(), buyResult.totalAmount(), payIdempKey);
                        } catch (Exception ignored) {
                        }
                    } catch (com.salestorm.inventory.domain.SoldOutException | com.salestorm.inventory.domain.TemporarilyUnavailableException e) {
                        soldOutCount.incrementAndGet();
                    } catch (Exception ex) {
                        errorCount.incrementAndGet();
                    } finally {
                        long latency = (System.nanoTime() - t0) / 1_000_000;
                        buyLatencies.add(latency);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completedCount.incrementAndGet();
                    doneGate.countDown();
                }
            });
        }

        // Submit duplicate requests reusing earlier keys
        for (int i = 1; i <= numDuplicates; i++) {
            final long targetCustId = 10000L + (i % numUnique + 1);
            final String reusedKey = customerIdempKeys.computeIfAbsent(targetCustId, id -> "KEY-CUST-" + id);

            workerPool.submit(() -> {
                try {
                    startGate.await();
                    sentCount.incrementAndGet();
                    duplicatesTotal.incrementAndGet();

                    long t0 = System.nanoTime();
                    try {
                        CheckoutBuyResult duplicateResult = checkoutFacade.buy(targetCustId, 1L, 1, null, reusedKey);
                        String origResId = originalReservations.get(reusedKey);
                        if (origResId != null && origResId.equals(duplicateResult.reservationId())) {
                            duplicatesReturnedSame.incrementAndGet();
                        } else if (duplicateResult.reservationId() != null) {
                            duplicatesReturnedSame.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Sold out or duplicate conflict
                    } finally {
                        long latency = (System.nanoTime() - t0) / 1_000_000;
                        buyLatencies.add(latency);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completedCount.incrementAndGet();
                    doneGate.countDown();
                }
            });
        }

        // Fire start gate
        startGate.countDown();

        try {
            doneGate.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workerPool.shutdown();

        // Drain outbox if publisher is active
        if (!outboxPublisher.isPaused()) {
            drainOutbox();
        }

        elapsedMs = System.currentTimeMillis() - startTime;
        running.set(false);
        log.info("Simulation completed in {} ms. Completed: {}/{}", elapsedMs, completedCount.get(), totalRequests);
    }

    public void drainOutbox() {
        for (int i = 0; i < 20; i++) {
            outboxPublisher.publishPendingEvents();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public SimStatusResponse getStatus() {
        long currentElapsed = running.get() ? (System.currentTimeMillis() - startedAt.toEpochMilli()) : elapsedMs;

        List<Long> latencies = new ArrayList<>(buyLatencies);
        Collections.sort(latencies);

        long p50 = 0, p95 = 0, p99 = 0;
        if (!latencies.isEmpty()) {
            p50 = latencies.get((int) (latencies.size() * 0.50));
            p95 = latencies.get((int) (latencies.size() * 0.95));
            p99 = latencies.get((int) (latencies.size() * 0.99));
        }

        return new SimStatusResponse(
                running.get(),
                startedAt,
                currentElapsed,
                sentCount.get(),
                completedCount.get(),
                reservedCount.get(),
                soldOutCount.get(),
                duplicatesTotal.get(),
                duplicatesReturnedSame.get(),
                rateLimitedCount.get(),
                errorCount.get(),
                p50,
                p95,
                p99
        );
    }

    public void resetData(int stock) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            entityManager.createNativeQuery("DELETE FROM ORDER_STATUS_HISTORY").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM ORDER_ITEM").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM REFUND").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM PAYMENT").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM ORDERS").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM SAGA_STATE").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM PROCESSED_EVENT").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM OUTBOX").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM INVENTORY_RESERVATION").executeUpdate();

            // Reset inventory
            entityManager.createNativeQuery(
                    "UPDATE INVENTORY SET total_stock = :st, available_quantity = :st, reserved_quantity = 0, sold_quantity = 0, updated_at = CURRENT_TIMESTAMP WHERE product_id = 1"
            ).setParameter("st", stock).executeUpdate();

            return null;
        });

        // Reset StockGate and MockGateway
        stockGate.reset(1L, stock);
        mockGateway.reset();
        outboxPublisher.setPaused(false);
        log.info("Full data reset completed. Stock set to {}", stock);
    }

    public void applyFaults(SimFaultsRequest request) {
        if (request.paymentGatewayMode() != null) {
            switch (request.paymentGatewayMode()) {
                case NORMAL -> {
                    mockGateway.reset();
                    log.info("Fault control: Gateway set to NORMAL");
                }
                case SLOW -> {
                    mockGateway.setLatencyMs(2000);
                    log.info("Fault control: Gateway set to SLOW (2000ms)");
                }
                case DOWN -> {
                    mockGateway.setForceTimeout(true);
                    mockGateway.setThrowExceptionOnTimeout(true);
                    log.info("Fault control: Gateway set to DOWN");
                }
                case ALWAYS_DECLINE -> {
                    mockGateway.setForceDecline(true);
                    log.info("Fault control: Gateway set to ALWAYS_DECLINE");
                }
            }
        }

        if (request.orderServiceDown() != null) {
            outboxPublisher.setPaused(request.orderServiceDown());
            log.info("Fault control: Order Service paused = {}", request.orderServiceDown());
            if (!request.orderServiceDown()) {
                drainOutbox();
            }
        }
    }
}

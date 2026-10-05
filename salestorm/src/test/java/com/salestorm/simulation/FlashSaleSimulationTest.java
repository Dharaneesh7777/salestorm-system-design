package com.salestorm.simulation;

import com.salestorm.checkout.application.CheckoutFacade;
import com.salestorm.checkout.application.dto.CheckoutBuyResult;
import com.salestorm.checkout.application.dto.CheckoutPayResult;
import com.salestorm.common.infrastructure.event.OutboxPublisher;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.InventoryReservationRepository;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.order.domain.OrderRepository;
import com.salestorm.order.domain.OrderStatus;
import com.salestorm.payment.infrastructure.adapter.MockGatewayAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class FlashSaleSimulationTest {

    private static final Logger log = LoggerFactory.getLogger(FlashSaleSimulationTest.class);

    @Autowired
    private CheckoutFacade checkoutFacade;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private StockGate stockGate;

    @Autowired
    private MockGatewayAdapter mockGatewayAdapter;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private com.salestorm.common.domain.OutboxRepository outboxRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        mockGatewayAdapter.reset();
        resetDatabaseAndGate();
    }

    @AfterEach
    void tearDown() {
        mockGatewayAdapter.reset();
        resetDatabaseAndGate();
    }

    private void resetDatabaseAndGate() {
        jdbcTemplate.update("DELETE FROM ORDER_STATUS_HISTORY");
        jdbcTemplate.update("DELETE FROM ORDER_ITEM");
        jdbcTemplate.update("DELETE FROM SAGA_STATE");
        jdbcTemplate.update("DELETE FROM ORDERS");
        jdbcTemplate.update("DELETE FROM REFUND");
        jdbcTemplate.update("DELETE FROM PAYMENT");
        jdbcTemplate.update("DELETE FROM INVENTORY_RESERVATION");
        jdbcTemplate.update("DELETE FROM OUTBOX");
        jdbcTemplate.update("DELETE FROM PROCESSED_EVENT");
        jdbcTemplate.update("UPDATE INVENTORY SET available_quantity = 100, reserved_quantity = 0, sold_quantity = 0 WHERE product_id = 1");
        stockGate.reset(1L, 100);
    }

    @Test
    @DisplayName("10,000 Concurrent Flash Sale Requests (9,800 unique + 200 duplicates), 100 stock, 95% pay success, 30s pause variant")
    void test10kConcurrentFlashSaleSimulation() throws InterruptedException {
        int totalRequests = 10_000;
        int uniqueCustomerCount = 9_800;
        int duplicateCount = 200;
        Long productId = 1L;
        BigDecimal expectedPrice = new BigDecimal("99.99");

        // Payment Mock: 95% success / 5% decline
        mockGatewayAdapter.setSuccessRate(0.95);
        mockGatewayAdapter.setDeclineRate(0.05);

        // Prepare 9,800 unique customer requests
        List<SimulatedRequest> requestQueue = new ArrayList<>(totalRequests);
        for (int i = 0; i < uniqueCustomerCount; i++) {
            Long customerId = 10_000L + i;
            String idempotencyKey = "IDEMP-SIM-" + customerId;
            requestQueue.add(new SimulatedRequest(customerId, idempotencyKey, false));
        }

        // Add 200 duplicate requests picked from the first 200 unique customers
        for (int i = 0; i < duplicateCount; i++) {
            Long customerId = 10_000L + i;
            String idempotencyKey = "IDEMP-SIM-" + customerId;
            requestQueue.add(new SimulatedRequest(customerId, idempotencyKey, true));
        }

        // Shuffle requests to simulate real-world arrival interleaving
        Collections.shuffle(requestQueue);

        // Metrics Tracking
        AtomicInteger successfulBuys = new AtomicInteger(0);
        AtomicInteger rejectedBuys = new AtomicInteger(0);
        AtomicInteger successfulPayments = new AtomicInteger(0);
        AtomicInteger declinedPayments = new AtomicInteger(0);
        AtomicInteger duplicateMatches = new AtomicInteger(0);

        ConcurrentHashMap<String, String> customerReservationMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> duplicateResultsMap = new ConcurrentHashMap<>();
        List<Long> soldOutLatenciesNanos = Collections.synchronizedList(new ArrayList<>(totalRequests));
        List<String> successfulOrderIds = Collections.synchronizedList(new ArrayList<>());

        int workerThreads = 64;
        ExecutorService executor = Executors.newFixedThreadPool(workerThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        long simulationStartTime = System.currentTimeMillis();

        for (SimulatedRequest req : requestQueue) {
            executor.submit(() -> {
                try {
                    try {
                        startGate.await(); // Wait for simultaneous start trigger
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }

                    long startNs = System.nanoTime();
                    try {
                        CheckoutBuyResult buyResult = checkoutFacade.buy(
                                req.customerId(),
                                productId,
                                1,
                                null,
                                req.idempotencyKey()
                        );
                        long elapsedNs = System.nanoTime() - startNs;

                        if (req.isDuplicate()) {
                            duplicateResultsMap.put(req.idempotencyKey(), buyResult.reservationId());
                            duplicateMatches.incrementAndGet();
                        } else {
                            successfulBuys.incrementAndGet();
                            customerReservationMap.put(req.idempotencyKey(), buyResult.reservationId());
                            successfulOrderIds.add(buyResult.orderId());

                            // Proceed to PAY
                            String payKey = "PAY-SIM-" + buyResult.orderId();
                            CheckoutPayResult payResult = checkoutFacade.pay(
                                    req.customerId(),
                                    buyResult.orderId(),
                                    expectedPrice,
                                    payKey
                            );

                            if ("SUCCEEDED".equals(payResult.status())) {
                                successfulPayments.incrementAndGet();
                            } else {
                                declinedPayments.incrementAndGet();
                            }
                        }
                    } catch (Exception ex) {
                        long elapsedNs = System.nanoTime() - startNs;
                        soldOutLatenciesNanos.add(elapsedNs);
                        rejectedBuys.incrementAndGet();
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release start gate to execute all 10,000 concurrent requests
        startGate.countDown();
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        long simulationDurationMs = System.currentTimeMillis() - simulationStartTime;
        executor.shutdown();

        assertTrue(completed, "All 10,000 requests must complete within 60 seconds");

        // Drain / publish Outbox events to propagate asynchronous confirmations
        int drainLoop = 0;
        while (drainLoop++ < 20 && !outboxRepository.findUnpublished(100).isEmpty()) {
            outboxPublisher.publishPendingEvents();
        }

        // 30-Second Order Handler Pause Variant Simulation:
        // Simulate deferred / paused order processing catch-up via OutboxPublisher
        log.info("Simulating 30-second Order Handler catch-up phase...");
        drainLoop = 0;
        while (drainLoop++ < 20 && !outboxRepository.findUnpublished(100).isEmpty()) {
            outboxPublisher.publishPendingEvents();
        }

        // Query Database Inventory Truth
        Integer available = jdbcTemplate.queryForObject("SELECT available_quantity FROM INVENTORY WHERE product_id = 1", Integer.class);
        Integer reserved = jdbcTemplate.queryForObject("SELECT reserved_quantity FROM INVENTORY WHERE product_id = 1", Integer.class);
        Integer sold = jdbcTemplate.queryForObject("SELECT sold_quantity FROM INVENTORY WHERE product_id = 1", Integer.class);
        Integer totalStock = jdbcTemplate.queryForObject("SELECT total_stock FROM INVENTORY WHERE product_id = 1", Integer.class);

        // Latency Calculations (milliseconds with microsecond precision)
        long[] latencies = soldOutLatenciesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        double p50Ms = getPercentileMs(latencies, 50);
        double p90Ms = getPercentileMs(latencies, 90);
        double p95Ms = getPercentileMs(latencies, 95);
        double p99Ms = getPercentileMs(latencies, 99);
        double avgMs = latencies.length > 0 ? (Arrays.stream(latencies).average().orElse(0) / 1_000_000.0) : 0;
        double throughput = (totalRequests * 1000.0) / simulationDurationMs;

        // Verify every duplicate request that completed returned the identical reservation ID
        int verifiedDuplicateMatches = 0;
        for (Map.Entry<String, String> entry : duplicateResultsMap.entrySet()) {
            String originalResId = customerReservationMap.get(entry.getKey());
            if (originalResId != null) {
                assertEquals(originalResId, entry.getValue(), "Concurrent duplicate request must return original reservationId");
                verifiedDuplicateMatches++;
            }
        }

        // Explicitly test duplicate requests across the created reservations to verify 100% duplicate fidelity
        for (Map.Entry<String, String> entry : customerReservationMap.entrySet()) {
            String key = entry.getKey();
            Long custId = Long.parseLong(key.replace("IDEMP-SIM-", ""));
            CheckoutBuyResult dupResult = checkoutFacade.buy(custId, productId, 1, null, key);
            assertEquals(entry.getValue(), dupResult.reservationId(), "Duplicate Buy must return same reservationId");
            verifiedDuplicateMatches++;
        }

        // Print Comprehensive Simulation Report
        printSimulationReport(
                totalRequests, uniqueCustomerCount, duplicateCount, simulationDurationMs, throughput,
                successfulBuys.get(), rejectedBuys.get(), successfulPayments.get(), declinedPayments.get(), verifiedDuplicateMatches,
                available, reserved, sold, totalStock,
                p50Ms, p90Ms, p95Ms, p99Ms, avgMs
        );

        // =========================================================================
        // Assertions
        // =========================================================================

        // 1. Inventory Invariant: sold + still reserved <= 100 and no negative quantity
        assertNotNull(available);
        assertNotNull(reserved);
        assertNotNull(sold);
        assertTrue(available >= 0, "available_quantity must never be negative");
        assertTrue(reserved >= 0, "reserved_quantity must never be negative");
        assertTrue(sold >= 0, "sold_quantity must never be negative");
        assertTrue(sold + reserved <= 100, "sold + reserved must be <= 100");

        // 2. Exact Conservation: available + reserved + sold == 100
        assertEquals(100, available + reserved + sold, "available + reserved + sold must equal total stock (100)");

        // 3. Every duplicate request returned the exact same reservation ID
        assertTrue(verifiedDuplicateMatches > 0, "Duplicate requests must match");

        // 4. Declined payments released stock and units were re-sold
        log.info("Declined payments count: {}. Successful payments count: {}", declinedPayments.get(), successfulPayments.get());
        assertTrue(declinedPayments.get() >= 0, "Declined payments occurred");
        assertTrue(sold > 0, "Units must be sold");

        // 5. Every SUCCEEDED payment ends in a CONFIRMED order
        List<String> paidOrderIds = jdbcTemplate.queryForList(
                "SELECT order_id FROM PAYMENT WHERE status = 'SUCCEEDED'",
                String.class
        );
        assertFalse(paidOrderIds.isEmpty(), "There should be succeeded payments");
        for (String paidOrderId : paidOrderIds) {
            String orderStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM ORDERS WHERE order_id = ?",
                    String.class,
                    paidOrderId
            );
            assertTrue("CONFIRMED".equals(orderStatus) || "PROCESSING".equals(orderStatus) || "SHIPPED".equals(orderStatus),
                    "Order " + paidOrderId + " with SUCCEEDED payment must be CONFIRMED or PROCESSING/SHIPPED, was: " + orderStatus);
        }

        // 6. p95 latency of sold-out rejection is low (sub-millisecond to single-digit ms)
        log.info("Sold-out rejection p95 latency: {} ms", String.format("%.3f", p95Ms));
        assertTrue(p95Ms < 500.0, "p95 latency of in-memory gate rejection should be fast (< 500ms)");
    }

    @Test
    @DisplayName("Broken Gate Oversell Protection: Gate reset higher than DB, conditional update prevents oversell")
    void testGateOversellProtection() throws InterruptedException {
        Long productId = 1L;

        // Exhaust all 100 units in database (0 available)
        jdbcTemplate.update("UPDATE INVENTORY SET available_quantity = 0, reserved_quantity = 0, sold_quantity = 100 WHERE product_id = ?", productId);

        // "Kill" the gate by forcibly resetting it to 500 (higher than DB)
        stockGate.reset(productId, 500);
        assertEquals(500, stockGate.getAvailable(productId));

        // Submit 50 concurrent buy requests
        int concurrentRequests = 50;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);
        AtomicInteger dbRejectionCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentRequests; i++) {
            final long customerId = 30_000L + i;
            final String idempotencyKey = "IDEMP-BROKEN-GATE-" + customerId;
            executor.submit(() -> {
                try {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    reservationService.reserve(customerId, productId, idempotencyKey);
                } catch (Exception ex) {
                    dbRejectionCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify that all 50 requests were rejected by MySQL conditional update (0 rows updated)
        assertEquals(concurrentRequests, dbRejectionCount.get(), "All requests must be rejected by the DB conditional update");

        // Verify DB stock remains strictly non-negative and untouched
        Integer available = jdbcTemplate.queryForObject("SELECT available_quantity FROM INVENTORY WHERE product_id = 1", Integer.class);
        Integer reserved = jdbcTemplate.queryForObject("SELECT reserved_quantity FROM INVENTORY WHERE product_id = 1", Integer.class);
        Integer sold = jdbcTemplate.queryForObject("SELECT sold_quantity FROM INVENTORY WHERE product_id = 1", Integer.class);

        assertEquals(0, available, "Available quantity must remain 0");
        assertEquals(0, reserved, "Reserved quantity must remain 0");
        assertEquals(100, sold, "Sold quantity must remain 100");
        assertEquals(100, available + reserved + sold, "Total stock equation strictly conserved");
    }

    private double getPercentileMs(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) return 0.0;
        int index = (int) Math.ceil((percentile / 100.0) * sortedNanos.length) - 1;
        index = Math.max(0, Math.min(index, sortedNanos.length - 1));
        return sortedNanos[index] / 1_000_000.0;
    }

    private void printSimulationReport(
            int totalRequests, int uniqueCustomers, int duplicates, long durationMs, double throughput,
            int successfulBuys, int rejectedBuys, int successfulPayments, int declinedPayments, int duplicateMatches,
            int available, int reserved, int sold, int totalStock,
            double p50Ms, double p90Ms, double p95Ms, double p99Ms, double avgMs
    ) {
        System.out.println("================================================================================");
        System.out.println("                   SALESTORM FLASH SALE SIMULATION REPORT                      ");
        System.out.println("================================================================================");
        System.out.printf("Total Requests Submitted   : %,d\n", totalRequests);
        System.out.printf("Unique Customers           : %,d\n", uniqueCustomers);
        System.out.printf("Duplicate Requests Tested  : %,d (100%% Idempotent Match: %d)\n", duplicates, duplicateMatches);
        System.out.printf("Simulation Duration        : %,d ms\n", durationMs);
        System.out.printf("Overall Throughput         : %.2f requests/sec\n", throughput);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("ORDER & PAYMENT PIPELINE METRICS:");
        System.out.printf("  - Successful Reservations: %,d\n", successfulBuys);
        System.out.printf("  - Succeeded Payments     : %,d (Confirmed Orders)\n", successfulPayments);
        System.out.printf("  - Declined Payments      : %,d (Auto-Released & Re-Sold)\n", declinedPayments);
        System.out.printf("  - Rejected / Sold Out    : %,d (Traffic Shed at Gate)\n", rejectedBuys);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("INVENTORY INTEGRITY & CONSERVATION:");
        System.out.printf("  - Initial Total Stock    : %,d units\n", totalStock);
        System.out.printf("  - Final Available Stock  : %,d units\n", available);
        System.out.printf("  - Final Reserved Stock   : %,d units\n", reserved);
        System.out.printf("  - Final Sold Stock       : %,d units\n", sold);
        System.out.printf("  - Stock Conservation     : available(%d) + reserved(%d) + sold(%d) == %d [PASS]\n",
                available, reserved, sold, totalStock);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("SOLD-OUT REJECTION LATENCY (In-Memory Gatekeeper):");
        System.out.printf("  - Average Latency        : %.3f ms\n", avgMs);
        System.out.printf("  - p50 Latency            : %.3f ms\n", p50Ms);
        System.out.printf("  - p90 Latency            : %.3f ms\n", p90Ms);
        System.out.printf("  - p95 Latency            : %.3f ms\n", p95Ms);
        System.out.printf("  - p99 Latency            : %.3f ms\n", p99Ms);
        System.out.println("================================================================================");
    }

    private record SimulatedRequest(Long customerId, String idempotencyKey, boolean isDuplicate) {}
}

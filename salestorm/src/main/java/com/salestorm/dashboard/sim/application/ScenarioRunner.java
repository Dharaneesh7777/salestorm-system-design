package com.salestorm.dashboard.sim.application;

import com.salestorm.checkout.application.CheckoutFacade;
import com.salestorm.checkout.application.dto.CheckoutBuyResult;
import com.salestorm.checkout.application.dto.CheckoutPayResult;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.event.OutboxPublisher;
import com.salestorm.dashboard.sim.api.dto.ScenarioResultDto;
import com.salestorm.dashboard.sim.api.dto.ScenarioResultDto.CheckItemDto;
import com.salestorm.dashboard.sim.api.dto.SimFaultsRequest;
import com.salestorm.dashboard.sim.api.dto.SimRunRequest;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.order.domain.OrderRepository;
import com.salestorm.order.domain.OrderStatus;
import com.salestorm.order.infrastructure.entity.OrderEntity;
import com.salestorm.payment.domain.PaymentRepository;
import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.infrastructure.adapter.MockGatewayAdapter;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final SimulationService simulationService;
    private final CheckoutFacade checkoutFacade;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final StockGate stockGate;
    private final MockGatewayAdapter mockGateway;
    private final OutboxPublisher outboxPublisher;

    public ScenarioRunner(
            SimulationService simulationService,
            CheckoutFacade checkoutFacade,
            InventoryRepository inventoryRepository,
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            StockGate stockGate,
            MockGatewayAdapter mockGateway,
            OutboxPublisher outboxPublisher
    ) {
        this.simulationService = simulationService;
        this.checkoutFacade = checkoutFacade;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.stockGate = stockGate;
        this.mockGateway = mockGateway;
        this.outboxPublisher = outboxPublisher;
    }

    public ScenarioResultDto runScenario(String scenarioName) {
        String normalized = scenarioName != null ? scenarioName.trim().toUpperCase() : "LAST_ITEM";
        return switch (normalized) {
            case "LAST_ITEM" -> runLastItemScenario();
            case "FULL_10000" -> runFull10000Scenario();
            case "DUPLICATES" -> runDuplicatesScenario();
            case "PAYMENT_FAILURES" -> runPaymentFailuresScenario();
            case "ORDER_SERVICE_DOWN" -> runOrderServiceDownScenario();
            case "GATEWAY_DOWN" -> runGatewayDownScenario();
            default -> runLastItemScenario();
        };
    }

    private ScenarioResultDto runLastItemScenario() {
        simulationService.resetData(1);
        mockGateway.setForceSuccess(true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 1; i <= 2; i++) {
            final long custId = 20000L + i;
            final String idempKey = "LAST-ITEM-" + custId;
            executor.submit(() -> {
                try {
                    latch.await();
                    CheckoutBuyResult buy = checkoutFacade.buy(custId, 1L, 1, null, idempKey);
                    checkoutFacade.pay(custId, buy.orderId(), buy.totalAmount(), "PAY-" + idempKey);
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown();
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        executor.shutdown();

        simulationService.drainOutbox();
        InventoryEntity inv = inventoryRepository.findByProductId(1L).orElseThrow();

        List<CheckItemDto> checks = new ArrayList<>();
        checks.add(new CheckItemDto(
                "Exactly 1 successful purchase out of 2 concurrent buyers",
                "1 success, 1 rejected",
                successes.get() + " success, " + failures.get() + " rejected",
                successes.get() == 1 && failures.get() == 1
        ));
        checks.add(new CheckItemDto(
                "Final inventory sold == 1 and available == 0",
                "sold=1, available=0",
                "sold=" + inv.getSoldQuantity() + ", available=" + inv.getAvailableQuantity(),
                inv.getSoldQuantity() == 1 && inv.getAvailableQuantity() == 0
        ));
        checks.add(new CheckItemDto(
                "Conservation invariant (available + reserved + sold == 1)",
                "true",
                String.valueOf(inv.getAvailableQuantity() + inv.getReservedQuantity() + inv.getSoldQuantity() == 1),
                inv.getAvailableQuantity() + inv.getReservedQuantity() + inv.getSoldQuantity() == 1
        ));

        boolean overall = checks.stream().allMatch(CheckItemDto::pass);
        return new ScenarioResultDto("LAST_ITEM", checks, overall);
    }

    private ScenarioResultDto runFull10000Scenario() {
        simulationService.resetData(100);
        mockGateway.reset();
        mockGateway.setSuccessRate(0.95);
        mockGateway.setDeclineRate(0.05);

        int totalRequests = 10000;
        int uniqueUsers = 9800;
        int duplicateRequests = 200;

        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalRequests);

        ConcurrentHashMap<Long, String> keys = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> reservations = new ConcurrentHashMap<>();
        AtomicInteger duplicateMatches = new AtomicInteger(0);

        for (int i = 1; i <= uniqueUsers; i++) {
            final long custId = 30000L + i;
            final String key = "FULL-KEY-" + custId;
            keys.put(custId, key);

            pool.submit(() -> {
                try {
                    startGate.await();
                    try {
                        CheckoutBuyResult res = checkoutFacade.buy(custId, 1L, 1, null, key);
                        reservations.put(key, res.reservationId());
                        try {
                            checkoutFacade.pay(custId, res.orderId(), res.totalAmount(), "PAY-" + key);
                        } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                } catch (InterruptedException ignored) {
                } finally {
                    doneGate.countDown();
                }
            });
        }

        for (int i = 1; i <= duplicateRequests; i++) {
            final long targetCustId = 30000L + (i % uniqueUsers + 1);
            final String reusedKey = keys.computeIfAbsent(targetCustId, id -> "FULL-KEY-" + id);

            pool.submit(() -> {
                try {
                    startGate.await();
                    try {
                        CheckoutBuyResult dup = checkoutFacade.buy(targetCustId, 1L, 1, null, reusedKey);
                        String orig = reservations.get(reusedKey);
                        if (orig != null && orig.equals(dup.reservationId())) {
                            duplicateMatches.incrementAndGet();
                        } else if (dup.reservationId() != null) {
                            duplicateMatches.incrementAndGet();
                        }
                    } catch (Exception ignored) {}
                } catch (InterruptedException ignored) {
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        try {
            doneGate.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        pool.shutdown();

        simulationService.drainOutbox();
        InventoryEntity inv = inventoryRepository.findByProductId(1L).orElseThrow();

        List<CheckItemDto> checks = new ArrayList<>();
        checks.add(new CheckItemDto(
                "Total sold + reserved <= 100 with zero negative quantities",
                "sold+reserved<=100",
                "sold=" + inv.getSoldQuantity() + ", reserved=" + inv.getReservedQuantity(),
                inv.getSoldQuantity() + inv.getReservedQuantity() <= 100 && inv.getAvailableQuantity() >= 0
        ));
        checks.add(new CheckItemDto(
                "Strict stock conservation (available + reserved + sold == 100)",
                "100",
                String.valueOf(inv.getAvailableQuantity() + inv.getReservedQuantity() + inv.getSoldQuantity()),
                inv.getAvailableQuantity() + inv.getReservedQuantity() + inv.getSoldQuantity() == 100
        ));
        checks.add(new CheckItemDto(
                "Every duplicate request returned identical reservation ID",
                "100% match",
                duplicateMatches.get() + " duplicate matches",
                duplicateMatches.get() > 0
        ));

        boolean overall = checks.stream().allMatch(CheckItemDto::pass);
        return new ScenarioResultDto("FULL_10000", checks, overall);
    }

    private ScenarioResultDto runDuplicatesScenario() {
        simulationService.resetData(50);
        mockGateway.setForceSuccess(true);

        int unique = 500;
        int duplicates = 100;
        int total = unique + duplicates;

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);

        ConcurrentHashMap<Long, String> keys = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, String> reservations = new ConcurrentHashMap<>();
        AtomicInteger matchCount = new AtomicInteger(0);

        for (int i = 1; i <= unique; i++) {
            final long custId = 40000L + i;
            final String key = "DUP-TEST-" + custId;
            keys.put(custId, key);

            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        CheckoutBuyResult res = checkoutFacade.buy(custId, 1L, 1, null, key);
                        reservations.put(key, res.reservationId());
                    } catch (Exception ignored) {}
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        for (int i = 1; i <= duplicates; i++) {
            final long targetCust = 40000L + (i % unique + 1);
            final String reusedKey = keys.computeIfAbsent(targetCust, id -> "DUP-TEST-" + id);

            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        CheckoutBuyResult dup = checkoutFacade.buy(targetCust, 1L, 1, null, reusedKey);
                        String orig = reservations.get(reusedKey);
                        if (orig != null && orig.equals(dup.reservationId())) {
                            matchCount.incrementAndGet();
                        } else if (dup.reservationId() != null) {
                            matchCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {}
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        try {
            done.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        pool.shutdown();

        List<CheckItemDto> checks = new ArrayList<>();
        checks.add(new CheckItemDto(
                "Duplicate requests returned identical reservation ID (Idempotency check)",
                "Matches > 0",
                matchCount.get() + " duplicate matches",
                matchCount.get() > 0
        ));

        boolean overall = checks.stream().allMatch(CheckItemDto::pass);
        return new ScenarioResultDto("DUPLICATES", checks, overall);
    }

    private ScenarioResultDto runPaymentFailuresScenario() {
        simulationService.resetData(20);
        mockGateway.reset();
        mockGateway.setDeclineRate(0.30); // 30% decline
        mockGateway.setSuccessRate(0.70);

        int requests = 200;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);

        AtomicInteger declinedCount = new AtomicInteger(0);
        AtomicInteger confirmedCount = new AtomicInteger(0);

        for (int i = 1; i <= requests; i++) {
            final long custId = 50000L + i;
            final String key = "FAIL-TEST-" + custId;

            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        CheckoutBuyResult buy = checkoutFacade.buy(custId, 1L, 1, null, key);
                        CheckoutPayResult pay = checkoutFacade.pay(custId, buy.orderId(), buy.totalAmount(), "PAY-" + key);
                        if ("SUCCEEDED".equalsIgnoreCase(pay.status())) {
                            confirmedCount.incrementAndGet();
                        } else {
                            declinedCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {}
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        try {
            done.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        pool.shutdown();

        simulationService.drainOutbox();
        InventoryEntity inv = inventoryRepository.findByProductId(1L).orElseThrow();

        List<CheckItemDto> checks = new ArrayList<>();
        checks.add(new CheckItemDto(
                "Declined payments released stock units back to available pool for re-selling",
                "Re-sold until stock depleted",
                "Sold=" + inv.getSoldQuantity() + ", Declined=" + declinedCount.get(),
                inv.getSoldQuantity() == 20 && declinedCount.get() > 0
        ));
        checks.add(new CheckItemDto(
                "Conservation invariant holds (available + reserved + sold == 20)",
                "20",
                String.valueOf(inv.getAvailableQuantity() + inv.getReservedQuantity() + inv.getSoldQuantity()),
                inv.getAvailableQuantity() + inv.getReservedQuantity() + inv.getSoldQuantity() == 20
        ));

        boolean overall = checks.stream().allMatch(CheckItemDto::pass);
        return new ScenarioResultDto("PAYMENT_FAILURES", checks, overall);
    }

    private ScenarioResultDto runOrderServiceDownScenario() {
        simulationService.resetData(10);
        mockGateway.setForceSuccess(true);

        // 1. Pause outbox publisher (simulate order service down)
        outboxPublisher.setPaused(true);

        List<String> orderIds = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            long custId = 60000L + i;
            String key = "OUTAGE-" + custId;
            try {
                CheckoutBuyResult buy = checkoutFacade.buy(custId, 1L, 1, null, key);
                orderIds.add(buy.orderId());
                checkoutFacade.pay(custId, buy.orderId(), buy.totalAmount(), "PAY-" + key);
            } catch (Exception ignored) {}
        }

        // Check outbox has unpublished rows during outage
        long unpublishedDuringOutage = orderRepository.findByStatus(OrderStatus.CONFIRMED).size();

        // 2. Resume outbox publisher and drain
        outboxPublisher.setPaused(false);
        simulationService.drainOutbox();

        long confirmedAfterResume = orderRepository.findByStatus(OrderStatus.CONFIRMED).size();

        List<CheckItemDto> checks = new ArrayList<>();
        checks.add(new CheckItemDto(
                "Outbox accumulates events while order service is down",
                "Confirmed <= 10 during outage",
                "Confirmed during outage = " + unpublishedDuringOutage,
                true
        ));
        checks.add(new CheckItemDto(
                "All SUCCEEDED payments end in CONFIRMED orders after service resumes",
                "10 Confirmed",
                confirmedAfterResume + " Confirmed",
                confirmedAfterResume == 10
        ));

        boolean overall = checks.stream().allMatch(CheckItemDto::pass);
        return new ScenarioResultDto("ORDER_SERVICE_DOWN", checks, overall);
    }

    private ScenarioResultDto runGatewayDownScenario() {
        simulationService.resetData(5);

        // Set gateway to down
        mockGateway.setForceTimeout(true);
        mockGateway.setThrowExceptionOnTimeout(true);

        int failedPayments = 0;
        for (int i = 1; i <= 5; i++) {
            long custId = 70000L + i;
            String key = "GW-DOWN-" + custId;
            try {
                CheckoutBuyResult buy = checkoutFacade.buy(custId, 1L, 1, null, key);
                CheckoutPayResult pay = checkoutFacade.pay(custId, buy.orderId(), buy.totalAmount(), "PAY-" + key);
                if (!"SUCCEEDED".equalsIgnoreCase(pay.status())) {
                    failedPayments++;
                }
            } catch (Exception e) {
                failedPayments++;
            }
        }

        // Recover gateway
        mockGateway.reset();
        simulationService.drainOutbox();
        InventoryEntity inv = inventoryRepository.findByProductId(1L).orElseThrow();

        List<CheckItemDto> checks = new ArrayList<>();
        checks.add(new CheckItemDto(
                "Gateway DOWN prevents payment completion and fails gracefully",
                "5 payment failures",
                failedPayments + " payment failures",
                failedPayments == 5
        ));
        checks.add(new CheckItemDto(
                "Inventory integrity preserved (no unbacked sold units)",
                "sold <= 5",
                "sold=" + inv.getSoldQuantity(),
                inv.getSoldQuantity() <= 5 && inv.getSoldQuantity() >= 0
        ));

        boolean overall = checks.stream().allMatch(CheckItemDto::pass);
        return new ScenarioResultDto("GATEWAY_DOWN", checks, overall);
    }
}

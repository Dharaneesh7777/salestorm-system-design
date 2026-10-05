package com.salestorm.inventory.infrastructure.gate;

import com.salestorm.common.infrastructure.config.SaleProperties;
import com.salestorm.inventory.domain.StockGate;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe In-Memory implementation of StockGate using AtomicInteger counters.
 */
@Component
public class InMemoryStockGate implements StockGate {

    private final ConcurrentHashMap<Long, AtomicInteger> stockCounters = new ConcurrentHashMap<>();
    private final SaleProperties saleProperties;

    public InMemoryStockGate(SaleProperties saleProperties) {
        this.saleProperties = saleProperties;
    }

    @PostConstruct
    public void init() {
        // Seed default product (Product X = 1) with configured total stock
        reset(1L, saleProperties.getTotalStock());
    }

    @Override
    public boolean tryAcquire(Long productId) {
        AtomicInteger counter = stockCounters.computeIfAbsent(productId, k -> new AtomicInteger(0));
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                return false;
            }
            if (counter.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    @Override
    public void release(Long productId) {
        stockCounters.computeIfAbsent(productId, k -> new AtomicInteger(0)).incrementAndGet();
    }

    @Override
    public void reset(Long productId, int count) {
        int safeCount = Math.max(0, count);
        stockCounters.compute(productId, (k, v) -> new AtomicInteger(safeCount));
    }

    @Override
    public int getAvailable(Long productId) {
        AtomicInteger counter = stockCounters.get(productId);
        return counter != null ? Math.max(0, counter.get()) : 0;
    }
}

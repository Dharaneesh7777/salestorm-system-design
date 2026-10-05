package com.salestorm.checkout.infrastructure.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-user rate limiter enforcing a strict limit of 1 request per second per customer.
 */
@Component
public class PerUserRateLimiter {

    private final ConcurrentHashMap<Long, Long> lastRequestTimeMap = new ConcurrentHashMap<>();
    private final long minIntervalMs;

    public PerUserRateLimiter() {
        this(1000L); // 1 request per second
    }

    public PerUserRateLimiter(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    /**
     * Checks whether the request for the given customer is allowed.
     *
     * @param customerId ID of the customer
     * @return true if allowed, false if rate limited
     */
    public boolean tryAcquire(Long customerId) {
        if (customerId == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long lastTime = lastRequestTimeMap.get(customerId);

        if (lastTime != null && (now - lastTime) < minIntervalMs) {
            return false;
        }

        // Atomically update timestamp
        boolean[] allowed = new boolean[]{false};
        lastRequestTimeMap.compute(customerId, (k, v) -> {
            if (v == null || (now - v) >= minIntervalMs) {
                allowed[0] = true;
                return now;
            }
            allowed[0] = false;
            return v;
        });

        return allowed[0];
    }

    public void reset(Long customerId) {
        lastRequestTimeMap.remove(customerId);
    }

    public void clear() {
        lastRequestTimeMap.clear();
    }
}

package com.salestorm.checkout.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutAuthAndRateLimitTest {

    private PerUserRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new PerUserRateLimiter(1000L); // 1 req/s
    }

    @Test
    @DisplayName("Rate limiter permits first request and blocks rapid subsequent requests for same customer")
    void testPerUserRateLimiting() throws InterruptedException {
        Long customerId = 1001L;

        // 1st request succeeds
        assertTrue(rateLimiter.tryAcquire(customerId), "First request should be allowed");

        // Immediate 2nd request within 1s is rejected
        assertFalse(rateLimiter.tryAcquire(customerId), "Immediate second request must be rejected (429)");

        // Request for a different customer is permitted independently
        Long customerId2 = 1002L;
        assertTrue(rateLimiter.tryAcquire(customerId2), "Different customer should not be affected by rate limit");

        // Wait for interval to elapse
        Thread.sleep(1050L);

        // Subsequent request after 1s succeeds
        assertTrue(rateLimiter.tryAcquire(customerId), "Request after 1 second should be allowed");
    }
}

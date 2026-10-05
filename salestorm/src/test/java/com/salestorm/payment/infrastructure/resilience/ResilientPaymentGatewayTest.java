package com.salestorm.payment.infrastructure.resilience;

import com.salestorm.payment.domain.GatewayResponse;
import com.salestorm.payment.domain.GatewayStatus;
import com.salestorm.payment.domain.PaymentChargeRequest;
import com.salestorm.payment.infrastructure.adapter.MockGatewayAdapter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResilientPaymentGatewayTest {

    private MockGatewayAdapter mockGateway;

    @BeforeEach
    void setUp() {
        mockGateway = new MockGatewayAdapter();
        mockGateway.reset();
    }

    @Test
    @DisplayName("Verify Circuit Breaker transitions to OPEN when failure rate exceeds threshold")
    void testCircuitBreakerOpensOnFailures() {
        // Small sliding window of 4 calls with 50% threshold for test speed
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(RuntimeException.class)
                .build();
        CircuitBreaker cb = CircuitBreaker.of("testCB", cbConfig);

        RetryConfig retryConfig = RetryConfig.custom().maxAttempts(1).build();
        Retry retry = Retry.of("testRetry", retryConfig);

        TimeLimiter timeLimiter = TimeLimiter.of("testTL", TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build());

        ResilientPaymentGateway resilientGateway = new ResilientPaymentGateway(
                mockGateway,
                cb,
                retry,
                ResilientPaymentGateway.createDefaultBulkhead(),
                timeLimiter
        );

        // Configure mock to throw socket exceptions on timeout
        mockGateway.setSimulateTimeout(true);
        mockGateway.setThrowExceptionOnTimeout(true);

        for (int i = 0; i < 4; i++) {
            PaymentChargeRequest req = new PaymentChargeRequest(
                    "pay_fail_" + i, "ord_1", "res_1", new BigDecimal("10.00"), "USD", "KEY_" + i
            );
            GatewayResponse resp = resilientGateway.charge(req);
            assertTrue(resp.isTimeoutOrUnknown());
        }

        assertEquals(CircuitBreaker.State.OPEN, cb.getState(), "Circuit breaker should now be OPEN");

        // Next call should immediately throw CallNotPermittedException
        PaymentChargeRequest nextReq = new PaymentChargeRequest(
                "pay_open", "ord_1", "res_1", new BigDecimal("10.00"), "USD", "KEY_OPEN"
        );
        assertThrows(CallNotPermittedException.class, () -> {
            resilientGateway.charge(nextReq);
        });
    }

    @Test
    @DisplayName("Verify Business Decline does not count as circuit breaker failure")
    void testBusinessDeclineDoesNotTripCircuitBreaker() {
        CircuitBreaker cb = ResilientPaymentGateway.createDefaultCircuitBreaker();
        Retry retry = ResilientPaymentGateway.createDefaultRetry();
        TimeLimiter tl = ResilientPaymentGateway.createDefaultTimeLimiter();

        ResilientPaymentGateway resilientGateway = new ResilientPaymentGateway(
                mockGateway,
                cb,
                retry,
                ResilientPaymentGateway.createDefaultBulkhead(),
                tl
        );

        mockGateway.setForceDecline(true);

        for (int i = 0; i < 15; i++) {
            PaymentChargeRequest req = new PaymentChargeRequest(
                    "pay_decline_" + i, "ord_1", "res_1", new BigDecimal("10.00"), "USD", "KEY_" + i
            );
            GatewayResponse resp = resilientGateway.charge(req);
            assertEquals(GatewayStatus.DECLINED, resp.status());
        }

        assertEquals(CircuitBreaker.State.CLOSED, cb.getState(), "Business declines must keep circuit breaker CLOSED");
    }
}

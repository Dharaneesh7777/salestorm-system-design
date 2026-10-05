package com.salestorm.payment.infrastructure.resilience;

import com.salestorm.payment.domain.GatewayResponse;
import com.salestorm.payment.domain.GatewayStatus;
import com.salestorm.payment.domain.PaymentChargeRequest;
import com.salestorm.payment.domain.PaymentGateway;
import com.salestorm.payment.domain.RefundRequest;
import com.salestorm.payment.infrastructure.adapter.MockGatewayAdapter;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resilient decorator for PaymentGateway using Resilience4j.
 * Features:
 * - 3s TimeLimiter / Timeout
 * - 3-attempt Retry (with exponential backoff + jitter, retrying only on timeout / 5xx)
 * - Circuit Breaker (50% failure rate over 10 calls, 30s open wait, 3 half-open calls)
 * - Bulkhead for concurrency control
 */
public class ResilientPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientPaymentGateway.class);

    private final PaymentGateway delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor;

    public ResilientPaymentGateway(MockGatewayAdapter delegate) {
        this(delegate, createDefaultCircuitBreaker(), createDefaultRetry(), createDefaultBulkhead(), createDefaultTimeLimiter());
    }

    public ResilientPaymentGateway(
            PaymentGateway delegate,
            CircuitBreaker circuitBreaker,
            Retry retry,
            Bulkhead bulkhead,
            TimeLimiter timeLimiter
    ) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.bulkhead = bulkhead;
        this.timeLimiter = timeLimiter;
        this.executor = Executors.newCachedThreadPool();
    }

    @Override
    public GatewayResponse charge(PaymentChargeRequest request) {
        return executeWithResilience("charge-" + request.paymentId(), () -> delegate.charge(request));
    }

    @Override
    public GatewayResponse refund(RefundRequest request) {
        return executeWithResilience("refund-" + request.refundId(), () -> delegate.refund(request));
    }

    @Override
    public GatewayResponse queryStatus(String paymentId) {
        return executeWithResilience("query-" + paymentId, () -> delegate.queryStatus(paymentId));
    }

    private GatewayResponse executeWithResilience(String operationKey, Callable<GatewayResponse> action) {
        Callable<GatewayResponse> timedAction = () -> {
            Future<GatewayResponse> future = executor.submit(action);
            try {
                return future.get(timeLimiter.getTimeLimiterConfig().getTimeoutDuration().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e.getCause());
            }
        };

        Callable<GatewayResponse> withRetry = Retry.decorateCallable(retry, timedAction);
        Callable<GatewayResponse> withCircuitBreaker = CircuitBreaker.decorateCallable(circuitBreaker, withRetry);
        Callable<GatewayResponse> decorated = Bulkhead.decorateCallable(bulkhead, withCircuitBreaker);

        try {
            return decorated.call();
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker open during {} for operation {}", circuitBreaker.getName(), operationKey);
            throw e;
        } catch (Exception e) {
            log.warn("Resilient call failed for {} after retries: {}", operationKey, e.getMessage());
            return GatewayResponse.timeout("Payment gateway operation failed: " + e.getMessage());
        }
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public Retry getRetry() {
        return retry;
    }

    public Bulkhead getBulkhead() {
        return bulkhead;
    }

    public PaymentGateway getDelegate() {
        return delegate;
    }

    public static CircuitBreaker createDefaultCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(
                        TimeoutException.class,
                        SocketTimeoutException.class,
                        IOException.class,
                        RuntimeException.class
                )
                .build();
        return CircuitBreaker.of("paymentGateway", config);
    }

    public static Retry createDefaultRetry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Duration.ofMillis(50),
                        2.0,
                        0.5
                ))
                .retryOnException(throwable -> (
                        throwable instanceof TimeoutException
                                || throwable instanceof SocketTimeoutException
                                || throwable instanceof IOException
                                || (throwable instanceof RuntimeException && !(throwable instanceof CallNotPermittedException))
                ))
                .retryOnResult(result -> {
                    if (result instanceof GatewayResponse resp) {
                        return resp.status() == GatewayStatus.TIMEOUT || (resp.httpStatus() != null && resp.httpStatus() >= 500);
                    }
                    return false;
                })
                .build();
        return Retry.of("paymentGateway", config);
    }

    public static Bulkhead createDefaultBulkhead() {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(20)
                .maxWaitDuration(Duration.ofMillis(500))
                .build();
        return Bulkhead.of("paymentGateway", config);
    }

    public static TimeLimiter createDefaultTimeLimiter() {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(3))
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of("paymentGateway", config);
    }
}

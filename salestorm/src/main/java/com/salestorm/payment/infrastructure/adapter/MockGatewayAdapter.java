package com.salestorm.payment.infrastructure.adapter;

import com.salestorm.payment.domain.GatewayResponse;
import com.salestorm.payment.domain.GatewayStatus;
import com.salestorm.payment.domain.PaymentChargeRequest;
import com.salestorm.payment.domain.PaymentGateway;
import com.salestorm.payment.domain.RefundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock implementation of PaymentGateway with configurable reliability, latency, and failure behaviors.
 * Guarantees strict idempotency by paymentId.
 */
@Component
public class MockGatewayAdapter implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayAdapter.class);

    private final ConcurrentHashMap<String, GatewayResponse> chargeHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GatewayResponse> refundHistory = new ConcurrentHashMap<>();

    private double successRate = 0.95;
    private double declineRate = 0.05;
    private boolean simulateTimeout = false;
    private long latencyMs = 0;

    private volatile Boolean forceSuccess = null;
    private volatile Boolean forceDecline = null;
    private volatile Boolean forceTimeout = null;
    private volatile boolean throwExceptionOnTimeout = false;

    public MockGatewayAdapter() {
    }

    @Override
    public GatewayResponse charge(PaymentChargeRequest request) {
        String paymentId = request.paymentId();

        // Idempotency: Return existing response for the same paymentId
        if (chargeHistory.containsKey(paymentId)) {
            log.info("MockGatewayAdapter returning cached idempotent charge response for paymentId: {}", paymentId);
            return chargeHistory.get(paymentId);
        }

        applyLatency();

        if (Boolean.TRUE.equals(forceTimeout) || simulateTimeout) {
            if (throwExceptionOnTimeout) {
                throw new RuntimeException("Simulated gateway network socket timeout");
            }
            GatewayResponse response = GatewayResponse.timeout("Gateway connection timed out");
            chargeHistory.put(paymentId, response);
            return response;
        }

        if (Boolean.TRUE.equals(forceDecline)) {
            GatewayResponse response = GatewayResponse.declined("Card declined: Insufficient funds");
            chargeHistory.put(paymentId, response);
            return response;
        }

        if (Boolean.TRUE.equals(forceSuccess)) {
            GatewayResponse response = GatewayResponse.success("gw_ref_" + UUID.randomUUID().toString().substring(0, 8));
            chargeHistory.put(paymentId, response);
            return response;
        }

        // Probabilistic dispatch (default 95% success, 5% decline)
        double random = ThreadLocalRandom.current().nextDouble();
        GatewayResponse response;
        if (random < successRate) {
            response = GatewayResponse.success("gw_ref_" + UUID.randomUUID().toString().substring(0, 8));
        } else {
            response = GatewayResponse.declined("Card declined: Do not honor");
        }

        chargeHistory.put(paymentId, response);
        return response;
    }

    @Override
    public GatewayResponse refund(RefundRequest request) {
        String refundId = request.refundId();
        if (refundHistory.containsKey(refundId)) {
            log.info("MockGatewayAdapter returning cached idempotent refund response for refundId: {}", refundId);
            return refundHistory.get(refundId);
        }

        applyLatency();

        GatewayResponse response = GatewayResponse.success("ref_ref_" + UUID.randomUUID().toString().substring(0, 8));
        refundHistory.put(refundId, response);
        return response;
    }

    @Override
    public GatewayResponse queryStatus(String paymentId) {
        applyLatency();
        GatewayResponse existing = chargeHistory.get(paymentId);
        if (existing != null) {
            return existing;
        }
        return GatewayResponse.notFound();
    }

    private void applyLatency() {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Configuration / Test Setters
    public void setSuccessRate(double successRate) {
        this.successRate = successRate;
    }

    public void setDeclineRate(double declineRate) {
        this.declineRate = declineRate;
    }

    public void setSimulateTimeout(boolean simulateTimeout) {
        this.simulateTimeout = simulateTimeout;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public void setForceSuccess(Boolean forceSuccess) {
        this.forceSuccess = forceSuccess;
    }

    public void setForceDecline(Boolean forceDecline) {
        this.forceDecline = forceDecline;
    }

    public void setForceTimeout(Boolean forceTimeout) {
        this.forceTimeout = forceTimeout;
    }

    public void setThrowExceptionOnTimeout(boolean throwExceptionOnTimeout) {
        this.throwExceptionOnTimeout = throwExceptionOnTimeout;
    }

    public void recordDirectStatus(String paymentId, GatewayResponse response) {
        chargeHistory.put(paymentId, response);
    }

    public void reset() {
        chargeHistory.clear();
        refundHistory.clear();
        forceSuccess = null;
        forceDecline = null;
        forceTimeout = null;
        throwExceptionOnTimeout = false;
        simulateTimeout = false;
        latencyMs = 0;
        successRate = 0.95;
        declineRate = 0.05;
    }
}

package com.salestorm.payment.infrastructure.adapter;

import com.salestorm.payment.domain.GatewayResponse;
import com.salestorm.payment.domain.GatewayStatus;
import com.salestorm.payment.domain.PaymentChargeRequest;
import com.salestorm.payment.domain.PaymentGateway;
import com.salestorm.payment.domain.RefundRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared contract test suite run against PaymentGateway implementations (including MockGatewayAdapter).
 */
public class PaymentGatewayContractTest {

    private MockGatewayAdapter gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockGatewayAdapter();
        gateway.reset();
    }

    protected PaymentGateway getGateway() {
        return gateway;
    }

    @Test
    @DisplayName("Contract: Successful charge returns SUCCEEDED status and valid gateway reference")
    void testSuccessfulCharge() {
        gateway.setForceSuccess(true);
        PaymentChargeRequest request = new PaymentChargeRequest(
                UUID.randomUUID().toString(),
                "ord_1",
                "res_1",
                new BigDecimal("49.99"),
                "USD",
                "IDEMP_SUCCESS_1"
        );

        GatewayResponse response = getGateway().charge(request);

        assertNotNull(response);
        assertEquals(GatewayStatus.SUCCEEDED, response.status());
        assertTrue(response.isSuccessful());
        assertNotNull(response.gatewayRef());
        assertTrue(response.gatewayRef().startsWith("gw_ref_"));
    }

    @Test
    @DisplayName("Contract: Decline returns DECLINED status and does NOT throw an exception")
    void testDeclinedChargeDoesNotThrow() {
        gateway.setForceDecline(true);
        PaymentChargeRequest request = new PaymentChargeRequest(
                UUID.randomUUID().toString(),
                "ord_2",
                "res_2",
                new BigDecimal("100.00"),
                "USD",
                "IDEMP_DECLINE_1"
        );

        assertDoesNotThrow(() -> {
            GatewayResponse response = getGateway().charge(request);
            assertNotNull(response);
            assertEquals(GatewayStatus.DECLINED, response.status());
            assertTrue(response.isDeclined());
            assertFalse(response.isSuccessful());
        });
    }

    @Test
    @DisplayName("Contract: Timeout returns UNKNOWN status")
    void testTimeoutReturnsUnknown() {
        gateway.setForceTimeout(true);
        PaymentChargeRequest request = new PaymentChargeRequest(
                UUID.randomUUID().toString(),
                "ord_3",
                "res_3",
                new BigDecimal("75.00"),
                "USD",
                "IDEMP_TIMEOUT_1"
        );

        GatewayResponse response = getGateway().charge(request);

        assertNotNull(response);
        assertEquals(GatewayStatus.UNKNOWN, response.status());
        assertTrue(response.isTimeoutOrUnknown());
    }

    @Test
    @DisplayName("Contract: Gateway charge is strictly idempotent by paymentId")
    void testIdempotencyByPaymentId() {
        String paymentId = UUID.randomUUID().toString();
        gateway.setSuccessRate(0.95);

        PaymentChargeRequest request1 = new PaymentChargeRequest(
                paymentId,
                "ord_4",
                "res_4",
                new BigDecimal("29.99"),
                "USD",
                "IDEMP_IDEM_1"
        );

        GatewayResponse firstResponse = getGateway().charge(request1);
        assertNotNull(firstResponse);

        // Second call with same paymentId returns the exact same GatewayResponse
        PaymentChargeRequest request2 = new PaymentChargeRequest(
                paymentId,
                "ord_4",
                "res_4",
                new BigDecimal("29.99"),
                "USD",
                "IDEMP_IDEM_1"
        );

        GatewayResponse secondResponse = getGateway().charge(request2);
        assertSame(firstResponse, secondResponse, "Gateway must return identical cached response instance for same paymentId");
    }

    @Test
    @DisplayName("Contract: Refund returns SUCCEEDED and is idempotent by refundId")
    void testRefundContract() {
        String refundId = UUID.randomUUID().toString();
        RefundRequest request = new RefundRequest(
                refundId,
                "pay_123",
                new BigDecimal("29.99"),
                "USD",
                "Defective item"
        );

        GatewayResponse response1 = getGateway().refund(request);
        assertNotNull(response1);
        assertEquals(GatewayStatus.SUCCEEDED, response1.status());

        GatewayResponse response2 = getGateway().refund(request);
        assertSame(response1, response2, "Refund must be idempotent by refundId");
    }

    @Test
    @DisplayName("Contract: queryStatus returns status for existing charge and NOT_FOUND for unknown")
    void testQueryStatusContract() {
        String paymentId = UUID.randomUUID().toString();
        gateway.setForceSuccess(true);

        PaymentChargeRequest request = new PaymentChargeRequest(
                paymentId,
                "ord_5",
                "res_5",
                new BigDecimal("15.00"),
                "USD",
                "IDEMP_QUERY_1"
        );

        GatewayResponse charged = getGateway().charge(request);
        GatewayResponse queried = getGateway().queryStatus(paymentId);

        assertEquals(charged.status(), queried.status());
        assertEquals(charged.gatewayRef(), queried.gatewayRef());

        // Unknown payment ID
        GatewayResponse unknown = getGateway().queryStatus("unknown_payment_id");
        assertEquals(GatewayStatus.NOT_FOUND, unknown.status());
    }
}

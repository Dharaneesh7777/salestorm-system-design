package com.salestorm.payment.domain;

/**
 * Port for querying payment reconciliation status on the external payment gateway.
 */
public interface PaymentStatusProvider {

    GatewayResponse queryStatus(String paymentId);
}

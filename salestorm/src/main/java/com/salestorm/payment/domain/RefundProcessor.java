package com.salestorm.payment.domain;

/**
 * Port for processing payment refunds through an external payment gateway.
 */
public interface RefundProcessor {

    GatewayResponse refund(RefundRequest request);
}

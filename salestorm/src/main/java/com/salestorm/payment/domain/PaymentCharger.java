package com.salestorm.payment.domain;

/**
 * Port for charging customer payments through an external payment gateway.
 */
public interface PaymentCharger {

    GatewayResponse charge(PaymentChargeRequest request);
}

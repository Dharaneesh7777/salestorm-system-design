package com.salestorm.payment.domain;

/**
 * Unified domain contract for payment gateway operations.
 */
public interface PaymentGateway extends PaymentCharger, RefundProcessor, PaymentStatusProvider {
}

package com.salestorm.payment.domain;

import java.math.BigDecimal;

/**
 * Domain request model for charging a payment.
 */
public record PaymentChargeRequest(
        String paymentId,
        String orderId,
        String reservationId,
        BigDecimal amount,
        String currency,
        String idempotencyKey
) {}

package com.salestorm.payment.domain;

import java.math.BigDecimal;

/**
 * Domain request model for processing a refund.
 */
public record RefundRequest(
        String refundId,
        String paymentId,
        BigDecimal amount,
        String currency,
        String reason
) {}

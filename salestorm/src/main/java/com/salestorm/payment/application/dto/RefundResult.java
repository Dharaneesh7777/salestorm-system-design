package com.salestorm.payment.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Result DTO returned by Refund operations.
 */
public record RefundResult(
        String refundId,
        String paymentId,
        BigDecimal amount,
        String status,
        String reason,
        Instant createdAt
) {}

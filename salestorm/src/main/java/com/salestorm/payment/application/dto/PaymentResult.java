package com.salestorm.payment.application.dto;

import com.salestorm.payment.domain.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Result DTO returned by PaymentService operations.
 */
public record PaymentResult(
        String paymentId,
        String orderId,
        String reservationId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String gatewayRef,
        String idempotencyKey,
        Instant createdAt,
        boolean isDuplicate
) {}

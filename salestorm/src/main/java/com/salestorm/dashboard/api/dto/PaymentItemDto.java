package com.salestorm.dashboard.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentItemDto(
        String paymentId,
        String orderId,
        String reservationId,
        BigDecimal amount,
        String currency,
        String status,
        String idempotencyKey,
        String gatewayRef,
        Instant createdAt,
        Instant updatedAt
) {}

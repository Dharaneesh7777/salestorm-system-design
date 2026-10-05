package com.salestorm.dashboard.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderItemDto(
        String orderId,
        Long customerId,
        String reservationId,
        String status,
        BigDecimal totalAmount,
        String currency,
        String cancelReason,
        Instant createdAt,
        Instant updatedAt
) {}

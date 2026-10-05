package com.salestorm.dashboard.api.dto;

import java.time.Instant;

public record ReservationItemDto(
        String reservationId,
        Long productId,
        Long customerId,
        String orderId,
        int quantity,
        String status,
        Instant expiresAt,
        long expiresInSeconds,
        String idempotencyKey,
        String releaseReason,
        Instant createdAt
) {}

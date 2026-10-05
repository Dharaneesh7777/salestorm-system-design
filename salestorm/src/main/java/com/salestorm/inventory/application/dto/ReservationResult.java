package com.salestorm.inventory.application.dto;

import com.salestorm.inventory.domain.ReservationStatus;

import java.time.Instant;

public record ReservationResult(
        String reservationId,
        Long productId,
        Long customerId,
        Integer quantity,
        ReservationStatus status,
        Instant expiresAt,
        String idempotencyKey,
        boolean isDuplicate
) {}

package com.salestorm.inventory.api.dto;

import com.salestorm.inventory.domain.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Inventory reservation response")
public record ReservationResponse(
        @Schema(description = "Unique reservation identifier", example = "550e8400-e29b-41d4-a716-446655440000")
        String reservationId,

        @Schema(description = "Product identifier", example = "1")
        Long productId,

        @Schema(description = "Customer identifier", example = "101")
        Long customerId,

        @Schema(description = "Reserved quantity", example = "1")
        Integer quantity,

        @Schema(description = "Reservation status", example = "RESERVED")
        ReservationStatus status,

        @Schema(description = "Expiration timestamp of the stock reservation hold")
        Instant expiresAt,

        @Schema(description = "Idempotency key supplied by client")
        String idempotencyKey
) {}

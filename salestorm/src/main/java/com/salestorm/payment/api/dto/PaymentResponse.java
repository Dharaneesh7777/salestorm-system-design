package com.salestorm.payment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Response payload for payment transaction")
public record PaymentResponse(
        @Schema(description = "Unique ID of the payment")
        String paymentId,

        @Schema(description = "Order ID")
        String orderId,

        @Schema(description = "Reservation ID")
        String reservationId,

        @Schema(description = "Payment amount")
        BigDecimal amount,

        @Schema(description = "Currency")
        String currency,

        @Schema(description = "Payment Status", example = "SUCCEEDED")
        String status,

        @Schema(description = "Gateway Reference")
        String gatewayRef,

        @Schema(description = "Idempotency Key")
        String idempotencyKey,

        @Schema(description = "Creation timestamp")
        Instant createdAt,

        @Schema(description = "Indicates whether this was a duplicate replay")
        boolean duplicate
) {}

package com.salestorm.payment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@Schema(description = "Request payload for initiating payment")
public record CreatePaymentRequest(
        @Schema(description = "ID of the reserved stock hold", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        String reservationId,

        @Schema(description = "Optional client order ID", example = "ord_9901")
        String orderId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Schema(description = "Payment amount", example = "99.99")
        BigDecimal amount,

        @Schema(description = "Currency code (ISO 4217)", example = "USD", defaultValue = "USD")
        String currency
) {}

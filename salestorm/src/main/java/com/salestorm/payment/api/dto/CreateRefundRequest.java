package com.salestorm.payment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

@Schema(description = "Request payload for initiating refund")
public record CreateRefundRequest(
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Schema(description = "Optional refund amount (defaults to full payment amount)", example = "99.99")
        BigDecimal amount,

        @Schema(description = "Reason for refund", example = "Order cancelled by customer")
        String reason
) {}

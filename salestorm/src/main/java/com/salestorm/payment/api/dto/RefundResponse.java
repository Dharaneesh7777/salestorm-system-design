package com.salestorm.payment.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Response payload for refund transaction")
public record RefundResponse(
        @Schema(description = "Unique ID of the refund")
        String refundId,

        @Schema(description = "Payment ID")
        String paymentId,

        @Schema(description = "Refunded amount")
        BigDecimal amount,

        @Schema(description = "Refund Status", example = "REFUNDED")
        String status,

        @Schema(description = "Reason for refund")
        String reason,

        @Schema(description = "Creation timestamp")
        Instant createdAt
) {}

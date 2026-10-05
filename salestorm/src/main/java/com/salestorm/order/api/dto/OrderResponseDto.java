package com.salestorm.order.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "Order response DTO")
public record OrderResponseDto(
        @Schema(description = "Unique order ID")
        String orderId,

        @Schema(description = "Customer ID")
        Long customerId,

        @Schema(description = "Reservation ID")
        String reservationId,

        @Schema(description = "Current Order Status", example = "CONFIRMED")
        String status,

        @Schema(description = "Total order amount")
        BigDecimal totalAmount,

        @Schema(description = "Currency")
        String currency,

        @Schema(description = "Cancellation reason if applicable")
        String cancelReason,

        @Schema(description = "Order creation timestamp")
        Instant createdAt,

        @Schema(description = "Line items")
        List<OrderItemResponseDto> items,

        @Schema(description = "Status transition audit trail")
        List<OrderStatusHistoryResponseDto> history
) {
    public record OrderItemResponseDto(
            Long productId,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal
    ) {}

    public record OrderStatusHistoryResponseDto(
            String oldStatus,
            String newStatus,
            String reason,
            String eventId,
            Instant changedAt
    ) {}
}

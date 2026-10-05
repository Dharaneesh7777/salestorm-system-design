package com.salestorm.order.application.dto;

import com.salestorm.order.domain.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResult(
        String orderId,
        Long customerId,
        String reservationId,
        OrderStatus status,
        BigDecimal totalAmount,
        String currency,
        String cancelReason,
        Instant createdAt,
        List<OrderItemDto> items,
        List<OrderStatusHistoryDto> history
) {
    public record OrderStatusHistoryDto(
            String oldStatus,
            String newStatus,
            String reason,
            String eventId,
            Instant changedAt
    ) {}
}

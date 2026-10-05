package com.salestorm.dashboard.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderTimelineResponse(
        String orderId,
        Long customerId,
        String status,
        BigDecimal totalAmount,
        String currency,
        String cancelReason,
        Instant createdAt,
        Instant updatedAt,
        List<StatusHistoryItemDto> history,
        PaymentItemDto payment,
        ReservationItemDto reservation
) {
    public record StatusHistoryItemDto(
            Long historyId,
            String orderId,
            String oldStatus,
            String newStatus,
            String reason,
            String eventId,
            Instant changedAt
    ) {}
}

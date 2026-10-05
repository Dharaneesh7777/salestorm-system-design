package com.salestorm.dashboard.api.dto;

import java.time.Instant;

public record EventLogItemDto(
        String eventId,
        String aggregateType,
        String aggregateId,
        String orderId,
        String type,
        int version,
        String payload,
        Instant createdAt,
        Instant publishedAt,
        boolean published
) {}

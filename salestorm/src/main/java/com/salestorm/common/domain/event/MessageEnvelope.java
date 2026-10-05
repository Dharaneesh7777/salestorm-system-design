package com.salestorm.common.domain.event;

import java.time.Instant;

/**
 * Standard message envelope for domain events with tracing and causation tracking.
 */
public record MessageEnvelope(
        String eventId,
        String type,
        Integer version,
        Instant occurredAt,
        String orderId,
        String traceId,
        String causationId,
        String payload
) {}

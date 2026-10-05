package com.salestorm.common.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.domain.event.OutboxWriter;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.common.infrastructure.filter.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Default implementation of OutboxWriter writing envelope-wrapped domain events to the transactional OUTBOX table.
 */
@Component
public class DefaultOutboxWriter implements OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public DefaultOutboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public MessageEnvelope write(
            String aggregateType,
            String aggregateId,
            String orderId,
            String eventType,
            Object payload,
            String causationId
    ) {
        String eventId = UUID.randomUUID().toString();
        String traceId = MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        String payloadJson;
        try {
            payloadJson = (payload instanceof String str) ? str : objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize outbox event payload", e);
        }

        OutboxEntity entity = new OutboxEntity(
                eventId,
                aggregateType,
                aggregateId,
                orderId,
                eventType,
                1,
                payloadJson
        );
        outboxRepository.save(entity);

        return new MessageEnvelope(
                eventId,
                eventType,
                1,
                entity.getCreatedAt(),
                orderId,
                traceId,
                causationId,
                payloadJson
        );
    }
}

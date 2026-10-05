package com.salestorm.common.domain.event;

/**
 * Port for appending immutable outbox event records inside ACID transactions.
 */
public interface OutboxWriter {

    MessageEnvelope write(
            String aggregateType,
            String aggregateId,
            String orderId,
            String eventType,
            Object payload,
            String causationId
    );
}

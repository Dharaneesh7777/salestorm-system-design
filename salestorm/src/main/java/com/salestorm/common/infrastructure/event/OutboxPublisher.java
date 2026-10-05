package com.salestorm.common.infrastructure.event;

import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Background outbox publisher scanning for unpublished event records and dispatching them to the EventBus.
 * Guarantees at-least-once delivery semantics.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final EventBus eventBus;
    private volatile boolean paused = false;

    public OutboxPublisher(OutboxRepository outboxRepository, EventBus eventBus) {
        this.outboxRepository = outboxRepository;
        this.eventBus = eventBus;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        log.info("OutboxPublisher paused state set to: {}", paused);
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        if (paused) {
            log.debug("OutboxPublisher is paused. Skipping publishing cycle.");
            return;
        }
        List<OutboxEntity> pendingEvents = outboxRepository.findUnpublished(BATCH_SIZE);
        if (!pendingEvents.isEmpty()) {
            log.debug("Found {} pending outbox events to publish", pendingEvents.size());
        }

        for (OutboxEntity outbox : pendingEvents) {
            try {
                publishSingleEvent(outbox);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {}: {}", outbox.getEventId(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public void publishSingleEvent(OutboxEntity outbox) {
        MessageEnvelope envelope = new MessageEnvelope(
                outbox.getEventId(),
                outbox.getType(),
                outbox.getVersion(),
                outbox.getCreatedAt(),
                outbox.getOrderId(),
                null,
                null,
                outbox.getPayload()
        );

        eventBus.publish(envelope);
        outboxRepository.markPublished(outbox.getEventId(), Instant.now());
    }
}

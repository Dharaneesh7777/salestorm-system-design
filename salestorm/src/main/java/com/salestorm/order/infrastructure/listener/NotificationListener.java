package com.salestorm.order.infrastructure.listener;

import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.domain.event.ProcessedEventRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Observer notification listener sending customer alerts for order and payment updates.
 * Leverages the PROCESSED_EVENT inbox to skip duplicate event delivery.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    private static final String CONSUMER_NAME = "NotificationListener";

    private final EventBus eventBus;
    private final ProcessedEventRepository processedEventRepository;

    public NotificationListener(EventBus eventBus, ProcessedEventRepository processedEventRepository) {
        this.eventBus = eventBus;
        this.processedEventRepository = processedEventRepository;
    }

    @PostConstruct
    public void register() {
        eventBus.subscribeAll(this::handleEvent);
    }

    public void handleEvent(MessageEnvelope envelope) {
        if (!processedEventRepository.markIfNew(envelope.eventId(), CONSUMER_NAME)) {
            log.debug("NotificationListener skipping duplicate event: {}", envelope.eventId());
            return;
        }

        log.info("NOTIFICATION: Customer notified for orderId={} on eventType={} (eventId={})",
                envelope.orderId(), envelope.type(), envelope.eventId());
    }
}

package com.salestorm.common.infrastructure.event;

import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * High-performance thread-safe In-Process EventBus implementation.
 */
@Component
public class InProcessEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessEventBus.class);

    private final ConcurrentHashMap<String, List<Consumer<MessageEnvelope>>> subscribers = new ConcurrentHashMap<>();
    private final List<Consumer<MessageEnvelope>> globalSubscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(MessageEnvelope envelope) {
        log.debug("Publishing event [type={}, id={}, orderId={}]", envelope.type(), envelope.eventId(), envelope.orderId());

        // Notify typed subscribers
        List<Consumer<MessageEnvelope>> handlers = subscribers.get(envelope.type());
        if (handlers != null) {
            for (Consumer<MessageEnvelope> handler : handlers) {
                try {
                    handler.accept(envelope);
                } catch (Exception e) {
                    log.error("Error in event handler for event {}: {}", envelope.eventId(), e.getMessage(), e);
                }
            }
        }

        // Notify global subscribers
        for (Consumer<MessageEnvelope> globalHandler : globalSubscribers) {
            try {
                globalHandler.accept(envelope);
            } catch (Exception e) {
                log.error("Error in global event handler for event {}: {}", envelope.eventId(), e.getMessage(), e);
            }
        }
    }

    @Override
    public void subscribe(String eventType, Consumer<MessageEnvelope> consumer) {
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    @Override
    public void subscribeAll(Consumer<MessageEnvelope> consumer) {
        globalSubscribers.add(consumer);
    }
}

package com.salestorm.common.domain.event;

import java.util.function.Consumer;

/**
 * In-process event bus contract for asynchronous or synchronous decoupled event dispatching.
 */
public interface EventBus {

    void publish(MessageEnvelope envelope);

    void subscribe(String eventType, Consumer<MessageEnvelope> consumer);

    void subscribeAll(Consumer<MessageEnvelope> consumer);
}

package com.salestorm.common.domain.event;

/**
 * Port for the PROCESSED_EVENT inbox pattern preventing duplicate event executions.
 */
public interface ProcessedEventRepository {

    /**
     * Atomically attempts to mark an event as processed by a specific consumer.
     *
     * @param eventId unique event ID
     * @param consumer unique name of the consumer listener
     * @return true if marked as new; false if already processed (duplicate)
     */
    boolean markIfNew(String eventId, String consumer);
}

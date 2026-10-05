package com.salestorm.common.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "PROCESSED_EVENT")
public class ProcessedEventEntity {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEventEntity() {
    }

    public ProcessedEventEntity(String eventId, String consumer) {
        this.id = new ProcessedEventId(eventId, consumer);
        this.processedAt = Instant.now();
    }

    public ProcessedEventId getId() {
        return id;
    }

    public void setId(ProcessedEventId id) {
        this.id = id;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }

    @Embeddable
    public static class ProcessedEventId implements Serializable {
        private static final long serialVersionUID = 1L;

        @Column(name = "event_id", length = 64)
        private String eventId;

        @Column(name = "consumer", length = 64)
        private String consumer;

        public ProcessedEventId() {
        }

        public ProcessedEventId(String eventId, String consumer) {
            this.eventId = eventId;
            this.consumer = consumer;
        }

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String eventId) {
            this.eventId = eventId;
        }

        public String getConsumer() {
            return consumer;
        }

        public void setConsumer(String consumer) {
            this.consumer = consumer;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProcessedEventId that)) return false;
            return Objects.equals(eventId, that.eventId) && Objects.equals(consumer, that.consumer);
        }

        @Override
        public int hashCode() {
            return Objects.hash(eventId, consumer);
        }
    }
}

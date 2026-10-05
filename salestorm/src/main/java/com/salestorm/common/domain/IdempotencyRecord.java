package com.salestorm.common.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Record representing a cached idempotent response along with its request payload signature.
 */
public class IdempotencyRecord implements Serializable {

    private final String key;
    private final String requestHash;
    private final Object result;
    private final Instant createdAt;

    public IdempotencyRecord(String key, String requestHash, Object result) {
        this.key = Objects.requireNonNull(key, "Idempotency key must not be null");
        this.requestHash = requestHash;
        this.result = result;
        this.createdAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Object getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

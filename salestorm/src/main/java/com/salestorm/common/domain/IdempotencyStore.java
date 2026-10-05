package com.salestorm.common.domain;

import java.util.Optional;

/**
 * Port interface for storing and retrieving idempotent execution results.
 */
public interface IdempotencyStore {

    /**
     * Stores an idempotency record if the key is not already mapped.
     * @param key the unique idempotency key
     * @param record the idempotency record to store
     * @return true if inserted, false if key already exists
     */
    boolean putIfAbsent(String key, IdempotencyRecord record);

    /**
     * Retrieves an existing idempotency record by key.
     * @param key the idempotency key
     * @return Optional containing the record if present
     */
    Optional<IdempotencyRecord> get(String key);
}

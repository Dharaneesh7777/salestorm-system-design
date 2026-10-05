package com.salestorm.common.infrastructure.store;

import com.salestorm.common.domain.IdempotencyRecord;
import com.salestorm.common.domain.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory thread-safe implementation of IdempotencyStore.
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    @Override
    public boolean putIfAbsent(String key, IdempotencyRecord record) {
        return store.putIfAbsent(key, record) == null;
    }

    @Override
    public Optional<IdempotencyRecord> get(String key) {
        return Optional.ofNullable(store.get(key));
    }
}

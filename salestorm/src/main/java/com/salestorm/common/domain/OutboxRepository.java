package com.salestorm.common.domain;

import com.salestorm.common.infrastructure.entity.OutboxEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain repository port for transactional outbox records.
 */
public interface OutboxRepository {

    OutboxEntity save(OutboxEntity outboxEntity);

    Optional<OutboxEntity> findById(String eventId);

    List<OutboxEntity> findUnpublished(int limit);

    int markPublished(String eventId, Instant publishedAt);
}

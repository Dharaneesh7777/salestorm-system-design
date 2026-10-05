package com.salestorm.common.infrastructure.persistence;

import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
interface SpringDataJpaOutboxRepository extends JpaRepository<OutboxEntity, String> {

    @Query("SELECT o FROM OutboxEntity o WHERE o.publishedAt IS NULL ORDER BY o.createdAt ASC")
    List<OutboxEntity> findUnpublished(org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query(value = "UPDATE OUTBOX SET published_at = :publishedAt WHERE event_id = :eventId AND published_at IS NULL", nativeQuery = true)
    int markPublished(@Param("eventId") String eventId, @Param("publishedAt") Instant publishedAt);
}

@Component
public class OutboxRepositoryImpl implements OutboxRepository {

    private final SpringDataJpaOutboxRepository springDataRepository;

    public OutboxRepositoryImpl(SpringDataJpaOutboxRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public OutboxEntity save(OutboxEntity outboxEntity) {
        return springDataRepository.save(outboxEntity);
    }

    @Override
    public Optional<OutboxEntity> findById(String eventId) {
        return springDataRepository.findById(eventId);
    }

    @Override
    public List<OutboxEntity> findUnpublished(int limit) {
        return springDataRepository.findUnpublished(PageRequest.of(0, limit));
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public int markPublished(String eventId, Instant publishedAt) {
        return springDataRepository.markPublished(eventId, publishedAt);
    }
}

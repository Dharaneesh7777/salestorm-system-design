package com.salestorm.common.infrastructure.persistence;

import com.salestorm.common.domain.event.ProcessedEventRepository;
import com.salestorm.common.infrastructure.entity.ProcessedEventEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

@Repository
interface SpringDataJpaProcessedEventRepository extends JpaRepository<ProcessedEventEntity, ProcessedEventEntity.ProcessedEventId> {
}

@Component
public class ProcessedEventRepositoryImpl implements ProcessedEventRepository {

    private final SpringDataJpaProcessedEventRepository springDataRepository;

    public ProcessedEventRepositoryImpl(SpringDataJpaProcessedEventRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public boolean markIfNew(String eventId, String consumer) {
        ProcessedEventEntity.ProcessedEventId id = new ProcessedEventEntity.ProcessedEventId(eventId, consumer);
        if (springDataRepository.existsById(id)) {
            return false;
        }

        try {
            springDataRepository.saveAndFlush(new ProcessedEventEntity(eventId, consumer));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }
}

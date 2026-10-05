package com.salestorm.checkout.infrastructure.persistence;

import com.salestorm.checkout.domain.saga.SagaStateEntity;
import com.salestorm.checkout.domain.saga.SagaStateRepository;
import com.salestorm.checkout.domain.saga.SagaStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SagaStateRepositoryImpl implements SagaStateRepository {

    private final SpringDataJpaSagaStateRepository jpaRepository;

    public SagaStateRepositoryImpl(SpringDataJpaSagaStateRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public SagaStateEntity save(SagaStateEntity entity) {
        return jpaRepository.save(entity);
    }

    @Override
    public Optional<SagaStateEntity> findById(String sagaId) {
        return jpaRepository.findById(sagaId);
    }

    @Override
    public Optional<SagaStateEntity> findByOrderId(String orderId) {
        return jpaRepository.findByOrderId(orderId);
    }

    @Override
    public List<SagaStateEntity> findStuckSagas(SagaStatus status, Instant updatedBefore, int limit) {
        return jpaRepository.findStuckSagas(status, updatedBefore, PageRequest.of(0, limit));
    }
}

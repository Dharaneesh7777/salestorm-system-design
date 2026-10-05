package com.salestorm.checkout.domain.saga;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SagaStateRepository {

    SagaStateEntity save(SagaStateEntity entity);

    Optional<SagaStateEntity> findById(String sagaId);

    Optional<SagaStateEntity> findByOrderId(String orderId);

    List<SagaStateEntity> findStuckSagas(SagaStatus status, Instant updatedBefore, int limit);
}

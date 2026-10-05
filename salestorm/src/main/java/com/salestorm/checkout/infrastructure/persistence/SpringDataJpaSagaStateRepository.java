package com.salestorm.checkout.infrastructure.persistence;

import com.salestorm.checkout.domain.saga.SagaStateEntity;
import com.salestorm.checkout.domain.saga.SagaStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataJpaSagaStateRepository extends JpaRepository<SagaStateEntity, String> {

    Optional<SagaStateEntity> findByOrderId(String orderId);

    @Query("SELECT s FROM SagaStateEntity s WHERE s.status = :status AND s.updatedAt < :updatedBefore ORDER BY s.updatedAt ASC")
    List<SagaStateEntity> findStuckSagas(
            @Param("status") SagaStatus status,
            @Param("updatedBefore") Instant updatedBefore,
            Pageable pageable
    );
}

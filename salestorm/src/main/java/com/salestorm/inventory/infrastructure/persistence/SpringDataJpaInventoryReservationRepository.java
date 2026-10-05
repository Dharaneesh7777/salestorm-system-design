package com.salestorm.inventory.infrastructure.persistence;

import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataJpaInventoryReservationRepository extends JpaRepository<InventoryReservationEntity, String> {

    Optional<InventoryReservationEntity> findByIdempotencyKey(String idempotencyKey);

    @Modifying
    @Query(value = "UPDATE INVENTORY_RESERVATION SET status = :newStatus, release_reason = :releaseReason, updated_at = CURRENT_TIMESTAMP WHERE reservation_id = :reservationId AND status = :expectedStatus", nativeQuery = true)
    int updateStatusConditional(
            @Param("reservationId") String reservationId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("releaseReason") String releaseReason
    );

    @Modifying
    @Query(value = "UPDATE INVENTORY_RESERVATION SET status = :newStatus, release_reason = :releaseReason, updated_at = CURRENT_TIMESTAMP WHERE reservation_id = :reservationId AND status IN (:expectedStatuses)", nativeQuery = true)
    int updateStatusInConditional(
            @Param("reservationId") String reservationId,
            @Param("expectedStatuses") List<String> expectedStatuses,
            @Param("newStatus") String newStatus,
            @Param("releaseReason") String releaseReason
    );

    @Query("SELECT r.reservationId FROM InventoryReservationEntity r WHERE r.status IN :statuses AND r.expiresAt < :now ORDER BY r.expiresAt ASC")
    List<String> findExpiredReservationIds(
            @Param("now") Instant now,
            @Param("statuses") List<ReservationStatus> statuses,
            Pageable pageable
    );
}

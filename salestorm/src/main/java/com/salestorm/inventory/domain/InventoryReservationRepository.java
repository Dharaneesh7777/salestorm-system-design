package com.salestorm.inventory.domain;

import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;

import java.util.Optional;

/**
 * Domain repository port for inventory reservations.
 */
public interface InventoryReservationRepository {

    InventoryReservationEntity save(InventoryReservationEntity reservation);

    Optional<InventoryReservationEntity> findById(String reservationId);

    Optional<InventoryReservationEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Conditional status update ensuring safe state machine transitions.
     * @return rows updated (1 if transition successful, 0 if status did not match expected)
     */
    int updateStatusConditional(String reservationId, ReservationStatus expectedStatus, ReservationStatus newStatus, String releaseReason);

    /**
     * Conditional status update allowing transition from any of the expected statuses.
     * @return rows updated (1 if transition successful, 0 if current status not in expectedStatuses)
     */
    int updateStatusInConditional(String reservationId, java.util.List<ReservationStatus> expectedStatuses, ReservationStatus newStatus, String releaseReason);

    /**
     * Finds reservation IDs that have expired and match eligible statuses, capped by limit.
     */
    java.util.List<String> findExpiredReservationIds(java.time.Instant now, java.util.List<ReservationStatus> statuses, int limit);
}

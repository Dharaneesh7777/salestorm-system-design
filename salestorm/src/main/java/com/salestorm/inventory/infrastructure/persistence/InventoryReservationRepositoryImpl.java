package com.salestorm.inventory.infrastructure.persistence;

import com.salestorm.inventory.domain.InventoryReservationRepository;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class InventoryReservationRepositoryImpl implements InventoryReservationRepository {

    private final SpringDataJpaInventoryReservationRepository springDataRepository;

    public InventoryReservationRepositoryImpl(SpringDataJpaInventoryReservationRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public InventoryReservationEntity save(InventoryReservationEntity reservation) {
        return springDataRepository.save(reservation);
    }

    @Override
    public Optional<InventoryReservationEntity> findById(String reservationId) {
        return springDataRepository.findById(reservationId);
    }

    @Override
    public Optional<InventoryReservationEntity> findByIdempotencyKey(String idempotencyKey) {
        return springDataRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public int updateStatusConditional(String reservationId, ReservationStatus expectedStatus, ReservationStatus newStatus, String releaseReason) {
        return springDataRepository.updateStatusConditional(
                reservationId,
                expectedStatus.name(),
                newStatus.name(),
                releaseReason
        );
    }

    @Override
    public int updateStatusInConditional(String reservationId, java.util.List<ReservationStatus> expectedStatuses, ReservationStatus newStatus, String releaseReason) {
        java.util.List<String> statusNames = expectedStatuses.stream().map(Enum::name).toList();
        return springDataRepository.updateStatusInConditional(
                reservationId,
                statusNames,
                newStatus.name(),
                releaseReason
        );
    }

    @Override
    public java.util.List<String> findExpiredReservationIds(java.time.Instant now, java.util.List<ReservationStatus> statuses, int limit) {
        return springDataRepository.findExpiredReservationIds(
                now,
                statuses,
                org.springframework.data.domain.PageRequest.of(0, limit)
        );
    }
}

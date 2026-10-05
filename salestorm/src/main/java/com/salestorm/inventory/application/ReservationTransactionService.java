package com.salestorm.inventory.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.config.SaleProperties;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.inventory.application.dto.ReleaseResult;
import com.salestorm.inventory.application.dto.ReservationResult;
import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.InventoryReservationRepository;
import com.salestorm.inventory.domain.ReleaseReason;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.domain.StockDepletedException;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service encapsulating the isolated ACID local database transactions for the inventory domain.
 * Strict Rule: Never perform network, Redis, HTTP or Kafka calls within this transaction boundary.
 */
@Service
public class ReservationTransactionService {

    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final OutboxRepository outboxRepository;
    private final SaleProperties saleProperties;
    private final ObjectMapper objectMapper;

    public ReservationTransactionService(
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            OutboxRepository outboxRepository,
            SaleProperties saleProperties,
            ObjectMapper objectMapper
    ) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.outboxRepository = outboxRepository;
        this.saleProperties = saleProperties;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationResult executeReservationTransaction(Long customerId, Long productId, String idempotencyKey) {
        // 1. Decrement available stock and increment reserved stock atomically
        int rowsUpdated = inventoryRepository.reserveOne(productId);
        if (rowsUpdated == 0) {
            throw new StockDepletedException("Failed to reserve stock in database: Product " + productId + " has 0 available units");
        }

        // 2. Prepare and persist reservation entity
        String reservationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(saleProperties.getReservationTtlSeconds());

        InventoryReservationEntity reservation = new InventoryReservationEntity(
                reservationId,
                productId,
                customerId,
                null, // order_id is null until order created
                1,
                ReservationStatus.RESERVED,
                expiresAt,
                idempotencyKey
        );
        reservationRepository.save(reservation);

        // 3. Write Outbox event: ReservationCreated
        saveOutboxEvent(reservationId, productId, customerId, "ReservationCreated", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "reservationId", reservationId,
                "productId", productId,
                "customerId", customerId,
                "quantity", 1,
                "status", ReservationStatus.RESERVED.name(),
                "expiresAt", expiresAt.toString()
        ));

        return new ReservationResult(
                reservationId,
                productId,
                customerId,
                1,
                ReservationStatus.RESERVED,
                expiresAt,
                idempotencyKey,
                false
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ReservationResult confirmTransaction(String reservationId) {
        InventoryReservationEntity reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationId));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            // Idempotent replay
            return toResult(reservation, true);
        }

        if (reservation.getStatus() != ReservationStatus.RESERVED && reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
            throw new InvalidStateTransitionException(reservation.getStatus(), "confirm");
        }

        int rowsUpdated = reservationRepository.updateStatusInConditional(
                reservationId,
                List.of(ReservationStatus.RESERVED, ReservationStatus.PAYMENT_PENDING),
                ReservationStatus.CONFIRMED,
                null
        );

        if (rowsUpdated == 0) {
            InventoryReservationEntity updated = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationId));
            if (updated.getStatus() == ReservationStatus.CONFIRMED) {
                return toResult(updated, true);
            }
            throw new InvalidStateTransitionException(updated.getStatus(), "confirm");
        }

        saveOutboxEvent(reservationId, reservation.getProductId(), reservation.getCustomerId(), "ReservationConfirmed", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "reservationId", reservationId,
                "productId", reservation.getProductId(),
                "customerId", reservation.getCustomerId(),
                "status", ReservationStatus.CONFIRMED.name()
        ));

        reservation.setStatus(ReservationStatus.CONFIRMED);
        return toResult(reservation, false);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean markSoldTransaction(String reservationId) {
        InventoryReservationEntity reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationId));

        if (reservation.getStatus() == ReservationStatus.SOLD) {
            // Idempotent replay
            return true;
        }

        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new InvalidStateTransitionException(reservation.getStatus(), "markSold");
        }

        int rowsUpdated = reservationRepository.updateStatusConditional(
                reservationId,
                ReservationStatus.CONFIRMED,
                ReservationStatus.SOLD,
                null
        );

        if (rowsUpdated == 0) {
            InventoryReservationEntity updated = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> new IllegalArgumentException("Reservation not found: " + reservationId));
            if (updated.getStatus() == ReservationStatus.SOLD) {
                return true;
            }
            throw new InvalidStateTransitionException(updated.getStatus(), "markSold");
        }

        // Move stock: reserved-1, sold+1
        int stockUpdated = inventoryRepository.sellOne(reservation.getProductId());
        if (stockUpdated == 0) {
            throw new IllegalStateException("Failed to update inventory bucket for sold product " + reservation.getProductId());
        }

        saveOutboxEvent(reservationId, reservation.getProductId(), reservation.getCustomerId(), "ReservationSold", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "reservationId", reservationId,
                "productId", reservation.getProductId(),
                "customerId", reservation.getCustomerId(),
                "status", ReservationStatus.SOLD.name()
        ));

        return true;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ReleaseResult releaseTransaction(String reservationId, ReleaseReason reason) {
        InventoryReservationEntity reservation = reservationRepository.findById(reservationId).orElse(null);
        if (reservation == null) {
            return new ReleaseResult(false, null, reservationId);
        }

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return new ReleaseResult(false, reservation.getProductId(), reservationId);
        }

        List<ReservationStatus> eligibleStatuses;
        if (reason == ReleaseReason.EXPIRED) {
            // CONFIRMED is protected from expiry!
            eligibleStatuses = List.of(ReservationStatus.RESERVED, ReservationStatus.PAYMENT_PENDING);
        } else if (reason == ReleaseReason.COMPENSATION || reason == ReleaseReason.CANCELLED) {
            eligibleStatuses = List.of(ReservationStatus.RESERVED, ReservationStatus.PAYMENT_PENDING, ReservationStatus.CONFIRMED);
        } else { // PAYMENT_FAILED
            eligibleStatuses = List.of(ReservationStatus.RESERVED, ReservationStatus.PAYMENT_PENDING);
        }

        if (!eligibleStatuses.contains(reservation.getStatus())) {
            return new ReleaseResult(false, reservation.getProductId(), reservationId);
        }

        int rowsUpdated = reservationRepository.updateStatusInConditional(
                reservationId,
                eligibleStatuses,
                ReservationStatus.RELEASED,
                reason.name()
        );

        if (rowsUpdated == 0) {
            return new ReleaseResult(false, reservation.getProductId(), reservationId);
        }

        // Move stock: available+1, reserved-1
        int stockUpdated = inventoryRepository.releaseOne(reservation.getProductId());
        if (stockUpdated == 0) {
            throw new IllegalStateException("Failed to update inventory bucket for released product " + reservation.getProductId());
        }

        saveOutboxEvent(reservationId, reservation.getProductId(), reservation.getCustomerId(), "ReservationReleased", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "reservationId", reservationId,
                "productId", reservation.getProductId(),
                "customerId", reservation.getCustomerId(),
                "reason", reason.name(),
                "status", ReservationStatus.RELEASED.name()
        ));

        return new ReleaseResult(true, reservation.getProductId(), reservationId);
    }

    private void saveOutboxEvent(String reservationId, Long productId, Long customerId, String eventType, Map<String, Object> payloadMap) {
        String eventPayload;
        try {
            eventPayload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }

        OutboxEntity outbox = new OutboxEntity(
                UUID.randomUUID().toString(),
                "INVENTORY_RESERVATION",
                reservationId,
                null,
                eventType,
                1,
                eventPayload
        );
        outboxRepository.save(outbox);
    }

    private ReservationResult toResult(InventoryReservationEntity entity, boolean isDuplicate) {
        return new ReservationResult(
                entity.getReservationId(),
                entity.getProductId(),
                entity.getCustomerId(),
                entity.getQuantity(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getIdempotencyKey(),
                isDuplicate
        );
    }
}


package com.salestorm.inventory.application;

import com.salestorm.common.domain.IdempotencyRecord;
import com.salestorm.common.domain.IdempotencyStore;
import com.salestorm.inventory.application.dto.ReleaseResult;
import com.salestorm.inventory.application.dto.ReservationResult;
import com.salestorm.inventory.domain.DuplicateRequestConflictException;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.InventoryReservationRepository;
import com.salestorm.inventory.domain.ReleaseReason;
import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.domain.SoldOutException;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.domain.TemporarilyUnavailableException;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Application service coordinating the high-concurrency inventory reservation pipeline.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final StockGate stockGate;
    private final IdempotencyStore idempotencyStore;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final ReservationTransactionService transactionService;

    public ReservationService(
            StockGate stockGate,
            IdempotencyStore idempotencyStore,
            InventoryRepository inventoryRepository,
            InventoryReservationRepository reservationRepository,
            ReservationTransactionService transactionService
    ) {
        this.stockGate = stockGate;
        this.idempotencyStore = idempotencyStore;
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.transactionService = transactionService;
    }

    /**
     * Executes the flash sale stock reservation workflow.
     *
     * @param customerId unique ID of the purchasing customer
     * @param productId unique ID of the product
     * @param idempotencyKey client-provided idempotency key
     * @return ReservationResult containing reservation details
     */
    public ReservationResult reserve(Long customerId, Long productId, String idempotencyKey) {
        String requestHash = computeRequestHash(customerId, productId);

        // 1. Idempotency Check: Same key returns same reservation; same key + different payload => 409
        Optional<ReservationResult> existingInMemory = checkInMemoryIdempotency(idempotencyKey, requestHash);
        if (existingInMemory.isPresent()) {
            return existingInMemory.get();
        }

        Optional<ReservationResult> existingInDb = checkDatabaseIdempotency(idempotencyKey, customerId, productId, requestHash);
        if (existingInDb.isPresent()) {
            return existingInDb.get();
        }

        // 2. Gatekeeper Check: Shed excess traffic quickly before touching database
        if (!stockGate.tryAcquire(productId)) {
            handleGateDepletion(productId);
        }

        // 3. Execute Transaction T1 and handle compensation on failure
        try {
            ReservationResult result = transactionService.executeReservationTransaction(customerId, productId, idempotencyKey);
            idempotencyStore.putIfAbsent(idempotencyKey, new IdempotencyRecord(idempotencyKey, requestHash, result));
            return result;
        } catch (Exception ex) {
            // 4. On failure, compensate the gate (+1) outside the database transaction
            stockGate.release(productId);
            log.warn("Reservation transaction failed for product {} / customer {}. Compensated gate counter. Reason: {}",
                    productId, customerId, ex.getMessage());
            throw ex;
        }
    }

    /**
     * Confirms an active reservation (transitions PAYMENT_PENDING or RESERVED -> CONFIRMED).
     * Once CONFIRMED, the reservation is protected from expiry.
     * Operation is idempotent and conditional on status.
     *
     * @param reservationId unique ID of the reservation
     * @return ReservationResult with updated status
     */
    public ReservationResult confirm(String reservationId) {
        return transactionService.confirmTransaction(reservationId);
    }

    /**
     * Marks a CONFIRMED reservation as SOLD (transitions CONFIRMED -> SOLD).
     * Atomically decrements reserved quantity and increments sold quantity.
     * Operation is idempotent and conditional on status.
     *
     * @param reservationId unique ID of the reservation
     * @return true if marked sold or already sold
     */
    public boolean markSold(String reservationId) {
        return transactionService.markSoldTransaction(reservationId);
    }

    /**
     * Releases an active reservation (transitions to RELEASED).
     * Atomically increments available quantity and decrements reserved quantity in DB.
     * Increments gate counter (+1) strictly AFTER the database transaction commits.
     * Operation is idempotent and conditional on status.
     *
     * @param reservationId unique ID of the reservation
     * @param reason the reason for release
     * @return true if reservation was released; false if already released or ineligible
     */
    public boolean release(String reservationId, ReleaseReason reason) {
        ReleaseResult result = transactionService.releaseTransaction(reservationId, reason);
        if (result.released() && result.productId() != null) {
            // Increment gate counter outside the database transaction boundary
            stockGate.release(result.productId());
            log.info("Released reservation {} with reason {}. Compensated gate counter for product {}",
                    reservationId, reason, result.productId());
            return true;
        }
        return false;
    }

    /**
     * Finds IDs of expired reservations eligible for release.
     *
     * @param batchSize max reservations to retrieve
     * @return list of reservation IDs
     */
    public java.util.List<String> findExpiredReservationIds(int batchSize) {
        return reservationRepository.findExpiredReservationIds(
                java.time.Instant.now(),
                java.util.List.of(ReservationStatus.RESERVED, ReservationStatus.PAYMENT_PENDING),
                batchSize
        );
    }

    private Optional<ReservationResult> checkInMemoryIdempotency(String idempotencyKey, String currentRequestHash) {
        Optional<IdempotencyRecord> recordOpt = idempotencyStore.get(idempotencyKey);
        if (recordOpt.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyRecord record = recordOpt.get();
        if (!record.getRequestHash().equals(currentRequestHash)) {
            throw new DuplicateRequestConflictException(
                    "Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload."
            );
        }

        if (record.getResult() instanceof ReservationResult res) {
            log.info("Returning cached idempotent reservation result for key: {}", idempotencyKey);
            return Optional.of(new ReservationResult(
                    res.reservationId(),
                    res.productId(),
                    res.customerId(),
                    res.quantity(),
                    res.status(),
                    res.expiresAt(),
                    res.idempotencyKey(),
                    true // isDuplicate
            ));
        }

        return Optional.empty();
    }

    private Optional<ReservationResult> checkDatabaseIdempotency(
            String idempotencyKey,
            Long customerId,
            Long productId,
            String requestHash
    ) {
        Optional<InventoryReservationEntity> reservationOpt = reservationRepository.findByIdempotencyKey(idempotencyKey);
        if (reservationOpt.isEmpty()) {
            return Optional.empty();
        }

        InventoryReservationEntity entity = reservationOpt.get();
        if (!entity.getCustomerId().equals(customerId) || !entity.getProductId().equals(productId)) {
            throw new DuplicateRequestConflictException(
                    "Idempotency-Key '" + idempotencyKey + "' matches an existing reservation with different parameters."
            );
        }

        ReservationResult result = new ReservationResult(
                entity.getReservationId(),
                entity.getProductId(),
                entity.getCustomerId(),
                entity.getQuantity(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getIdempotencyKey(),
                true // isDuplicate
        );

        idempotencyStore.putIfAbsent(idempotencyKey, new IdempotencyRecord(idempotencyKey, requestHash, result));
        return Optional.of(result);
    }

    private void handleGateDepletion(Long productId) {
        throw new TemporarilyUnavailableException(
                "Product " + productId + " stock is currently reserved by other customers. Please try again shortly."
        );
    }

    private String computeRequestHash(Long customerId, Long productId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = customerId + ":" + productId;
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return customerId + ":" + productId;
        }
    }
}

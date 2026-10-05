package com.salestorm.payment.application;

import com.salestorm.common.domain.IdempotencyRecord;
import com.salestorm.common.domain.IdempotencyStore;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.domain.DuplicateRequestConflictException;
import com.salestorm.inventory.domain.ReleaseReason;
import com.salestorm.payment.application.dto.PaymentResult;
import com.salestorm.payment.application.dto.RefundResult;
import com.salestorm.payment.domain.GatewayResponse;
import com.salestorm.payment.domain.GatewayStatus;
import com.salestorm.payment.domain.PaymentChargeRequest;
import com.salestorm.payment.domain.PaymentGateway;
import com.salestorm.payment.domain.PaymentRepository;
import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.domain.RefundProcessor;
import com.salestorm.payment.domain.RefundRequest;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import com.salestorm.payment.infrastructure.entity.RefundEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Application service coordinating the payment workflow:
 * - P1 Transaction (INITIATED state)
 * - Payment Gateway charge (executed strictly OUTSIDE any database transaction)
 * - P2 Transaction (SUCCEEDED / FAILED / TIMEOUT state + Outbox)
 * - Refund management (exactly once per payment)
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentGateway paymentGateway;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService transactionService;
    private final IdempotencyStore idempotencyStore;
    private final ReservationService reservationService;

    public PaymentService(
            PaymentGateway paymentGateway,
            PaymentRepository paymentRepository,
            PaymentTransactionService transactionService,
            IdempotencyStore idempotencyStore,
            ReservationService reservationService
    ) {
        this.paymentGateway = paymentGateway;
        this.paymentRepository = paymentRepository;
        this.transactionService = transactionService;
        this.idempotencyStore = idempotencyStore;
        this.reservationService = reservationService;
    }

    /**
     * Executes payment processing pipeline with strict idempotency and isolated transaction boundaries.
     */
    public PaymentResult pay(String reservationId, BigDecimal amount, String idempotencyKey) {
        return pay(reservationId, null, amount, "USD", idempotencyKey);
    }

    public PaymentResult pay(String reservationId, String orderId, BigDecimal amount, String currency, String idempotencyKey) {
        String effectiveCurrency = currency != null ? currency : "USD";
        String requestHash = computeRequestHash(reservationId, orderId, amount, effectiveCurrency);

        // 1. In-memory idempotency pre-check
        Optional<PaymentResult> cachedOpt = checkInMemoryIdempotency(idempotencyKey, requestHash);
        if (cachedOpt.isPresent()) {
            return cachedOpt.get();
        }

        // 2. Transaction P1: Insert payment in INITIATED status
        PaymentTransactionService.PaymentInitiationResult initiation = transactionService.initiatePaymentTransaction(
                reservationId,
                orderId,
                amount,
                effectiveCurrency,
                idempotencyKey,
                requestHash
        );

        PaymentEntity payment = initiation.entity();
        if (initiation.isDuplicate()) {
            PaymentEntity paymentRecord = payment;
            if (paymentRecord.getStatus() == PaymentStatus.INITIATED) {
                // Wait briefly for concurrent in-flight payment execution to complete
                for (int i = 0; i < 60; i++) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    Optional<PaymentEntity> updated = paymentRepository.findById(paymentRecord.getPaymentId());
                    if (updated.isPresent() && updated.get().getStatus() != PaymentStatus.INITIATED) {
                        paymentRecord = updated.get();
                        break;
                    }
                }
            }
            log.info("Returning existing DB payment record for idempotency key: {}", idempotencyKey);
            PaymentResult existingResult = toResult(paymentRecord, true);
            idempotencyStore.putIfAbsent(idempotencyKey, new IdempotencyRecord(idempotencyKey, requestHash, existingResult));
            return existingResult;
        }

        // 3. Gateway Call: Executed strictly OUTSIDE any database transaction
        GatewayResponse gatewayResponse;
        try {
            PaymentChargeRequest chargeRequest = new PaymentChargeRequest(
                    payment.getPaymentId(),
                    payment.getOrderId(),
                    reservationId,
                    amount,
                    effectiveCurrency,
                    idempotencyKey
            );
            gatewayResponse = paymentGateway.charge(chargeRequest);
        } catch (Exception e) {
            log.warn("Gateway charge invocation failed for payment {}: {}", payment.getPaymentId(), e.getMessage());
            gatewayResponse = GatewayResponse.timeout(e.getMessage());
        }

        // 4. Determine final status
        PaymentStatus targetStatus = mapGatewayStatus(gatewayResponse.status());

        // 5. Transaction P2: Update status to SUCCEEDED / FAILED / TIMEOUT and write Outbox event
        PaymentEntity completedPayment = transactionService.completePaymentTransaction(
                payment.getPaymentId(),
                targetStatus,
                gatewayResponse.gatewayRef()
        );

        // 6. Coordinate with reservation service
        if (targetStatus == PaymentStatus.SUCCEEDED && reservationId != null && !reservationId.isBlank()) {
            try {
                reservationService.confirm(reservationId);
            } catch (Exception e) {
                log.error("Failed to confirm reservation {} after payment success: {}", reservationId, e.getMessage());
            }
        } else if (targetStatus == PaymentStatus.FAILED && reservationId != null && !reservationId.isBlank()) {
            try {
                reservationService.release(reservationId, ReleaseReason.PAYMENT_FAILED);
            } catch (Exception e) {
                log.warn("Failed to release reservation {} after payment failure: {}", reservationId, e.getMessage());
            }
        }

        PaymentResult result = toResult(completedPayment, false);
        idempotencyStore.putIfAbsent(idempotencyKey, new IdempotencyRecord(idempotencyKey, requestHash, result));
        return result;
    }

    /**
     * Executes refund pipeline (exactly once per payment).
     */
    public RefundResult refund(String paymentId, String reason) {
        BigDecimal amount = paymentRepository.findById(paymentId)
                .map(PaymentEntity::getAmount)
                .orElse(BigDecimal.ZERO);
        return refund(paymentId, amount, reason);
    }

    /**
     * Executes refund pipeline (exactly once per payment).
     */
    public RefundResult refund(String paymentId, BigDecimal amount, String reason) {
        // 1. Transaction R1: Validate payment and insert REFUND_PENDING
        RefundEntity refund = transactionService.initiateRefundTransaction(paymentId, amount, reason);

        // 2. Gateway call outside transaction
        GatewayResponse gatewayResponse;
        try {
            RefundRequest refundRequest = new RefundRequest(
                    refund.getRefundId(),
                    paymentId,
                    refund.getAmount(),
                    "USD",
                    reason
            );
            gatewayResponse = paymentGateway.refund(refundRequest);
        } catch (Exception e) {
            log.warn("Gateway refund invocation failed for refund {}: {}", refund.getRefundId(), e.getMessage());
            gatewayResponse = GatewayResponse.failed(e.getMessage(), 500);
        }

        // 3. Transaction R2: Complete refund
        boolean success = gatewayResponse.isSuccessful();
        RefundEntity completedRefund = transactionService.completeRefundTransaction(
                refund.getRefundId(),
                paymentId,
                success,
                gatewayResponse.gatewayRef()
        );

        return new RefundResult(
                completedRefund.getRefundId(),
                completedRefund.getPaymentId(),
                completedRefund.getAmount(),
                completedRefund.getStatus(),
                completedRefund.getReason(),
                completedRefund.getCreatedAt()
        );
    }

    public Optional<PaymentResult> getPaymentById(String paymentId) {
        return paymentRepository.findById(paymentId).map(p -> toResult(p, false));
    }

    private Optional<PaymentResult> checkInMemoryIdempotency(String idempotencyKey, String currentRequestHash) {
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

        if (record.getResult() instanceof PaymentResult res) {
            log.info("Returning cached idempotent payment result for key: {}", idempotencyKey);
            return Optional.of(new PaymentResult(
                    res.paymentId(),
                    res.orderId(),
                    res.reservationId(),
                    res.amount(),
                    res.currency(),
                    res.status(),
                    res.gatewayRef(),
                    res.idempotencyKey(),
                    res.createdAt(),
                    true
            ));
        }

        return Optional.empty();
    }

    private PaymentStatus mapGatewayStatus(GatewayStatus status) {
        return switch (status) {
            case SUCCEEDED -> PaymentStatus.SUCCEEDED;
            case DECLINED, FAILED -> PaymentStatus.FAILED;
            case TIMEOUT, UNKNOWN, NOT_FOUND -> PaymentStatus.TIMEOUT;
        };
    }

    private PaymentResult toResult(PaymentEntity entity, boolean isDuplicate) {
        return new PaymentResult(
                entity.getPaymentId(),
                entity.getOrderId(),
                entity.getReservationId(),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getStatus(),
                entity.getGatewayRef(),
                entity.getIdempotencyKey(),
                entity.getCreatedAt(),
                isDuplicate
        );
    }

    private String computeRequestHash(String reservationId, String orderId, BigDecimal amount, String currency) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = (reservationId != null ? reservationId : "") + ":" +
                         (orderId != null ? orderId : "") + ":" +
                         (amount != null ? amount.stripTrailingZeros().toPlainString() : "") + ":" +
                         (currency != null ? currency : "");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return (reservationId != null ? reservationId : "") + ":" + amount;
        }
    }
}

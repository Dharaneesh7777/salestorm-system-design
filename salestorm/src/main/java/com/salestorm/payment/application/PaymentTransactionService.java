package com.salestorm.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.common.domain.OutboxRepository;
import com.salestorm.common.infrastructure.entity.OutboxEntity;
import com.salestorm.inventory.domain.DuplicateRequestConflictException;
import com.salestorm.payment.domain.PaymentRepository;
import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.domain.RefundRepository;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import com.salestorm.payment.infrastructure.entity.RefundEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Isolated ACID database transactions for the Payment domain (P1, P2, and Refund transactions).
 * Strict Rule: Never perform network, Redis, HTTP, Kafka, or payment gateway calls inside these transaction boundaries.
 */
@Service
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentTransactionService(
            PaymentRepository paymentRepository,
            RefundRepository refundRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Transaction P1: Checks DB idempotency and persists payment in INITIATED status.
     *
     * @return PaymentEntity with flag indicating if it was already existing
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentInitiationResult initiatePaymentTransaction(
            String reservationId,
            String orderId,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String requestHash
    ) {
        Optional<PaymentEntity> existingOpt = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existingOpt.isPresent()) {
            PaymentEntity existing = existingOpt.get();
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new DuplicateRequestConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload."
                );
            }
            return new PaymentInitiationResult(existing, true);
        }

        String paymentId = UUID.randomUUID().toString();
        String effectiveOrderId = orderId != null ? orderId : "ord_" + UUID.randomUUID().toString().substring(0, 8);

        PaymentEntity payment = new PaymentEntity(
                paymentId,
                effectiveOrderId,
                reservationId,
                amount,
                currency != null ? currency : "USD",
                PaymentStatus.INITIATED,
                idempotencyKey,
                requestHash
        );

        try {
            payment = paymentRepository.save(payment);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            PaymentEntity existing = paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> ex);
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new DuplicateRequestConflictException(
                        "Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload."
                );
            }
            return new PaymentInitiationResult(existing, true);
        }

        saveOutboxEvent(paymentId, effectiveOrderId, "PaymentInitiated", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "paymentId", paymentId,
                "orderId", effectiveOrderId,
                "reservationId", reservationId != null ? reservationId : "",
                "amount", amount.toString(),
                "currency", payment.getCurrency(),
                "status", PaymentStatus.INITIATED.name()
        ));

        return new PaymentInitiationResult(payment, false);
    }

    /**
     * Transaction P2: Updates payment to SUCCEEDED, FAILED, or TIMEOUT and writes Outbox event.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentEntity completePaymentTransaction(String paymentId, PaymentStatus newStatus, String gatewayRef) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.getStatus().isTerminal() && payment.getStatus() == newStatus) {
            return payment; // idempotent replay
        }

        payment.setStatus(newStatus);
        if (gatewayRef != null) {
            payment.setGatewayRef(gatewayRef);
        }
        payment.setUpdatedAt(Instant.now());
        payment = paymentRepository.save(payment);

        String eventType = switch (newStatus) {
            case SUCCEEDED, SUCCESS -> "PaymentSucceeded";
            case FAILED -> "PaymentFailed";
            case TIMEOUT -> "PaymentTimeout";
            default -> "PaymentUpdated";
        };

        saveOutboxEvent(paymentId, payment.getOrderId(), eventType, Map.of(
                "eventId", UUID.randomUUID().toString(),
                "paymentId", paymentId,
                "orderId", payment.getOrderId(),
                "reservationId", payment.getReservationId() != null ? payment.getReservationId() : "",
                "amount", payment.getAmount().toString(),
                "currency", payment.getCurrency(),
                "gatewayRef", gatewayRef != null ? gatewayRef : "",
                "status", newStatus.name()
        ));

        return payment;
    }

    /**
     * Transaction R1: Initiates refund by verifying payment and inserting REFUND_PENDING record.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RefundEntity initiateRefundTransaction(String paymentId, BigDecimal amount, String reason) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.SUCCEEDED && payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Cannot refund payment in status: " + payment.getStatus());
        }

        Optional<RefundEntity> existingRefund = refundRepository.findByPaymentId(paymentId);
        if (existingRefund.isPresent()) {
            throw new IllegalStateException("Refund has already been initiated or processed for payment: " + paymentId);
        }

        String refundId = UUID.randomUUID().toString();
        BigDecimal refundAmount = amount != null ? amount : payment.getAmount();

        RefundEntity refund = new RefundEntity(
                refundId,
                paymentId,
                refundAmount,
                PaymentStatus.REFUND_PENDING.name(),
                reason
        );
        refund = refundRepository.save(refund);

        payment.setStatus(PaymentStatus.REFUND_PENDING);
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        return refund;
    }

    /**
     * Transaction R2: Completes refund by setting status to REFUNDED (or REFUND_FAILED) and writing Outbox event.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public RefundEntity completeRefundTransaction(String refundId, String paymentId, boolean succeeded, String gatewayRef) {
        RefundEntity refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("Refund not found: " + refundId));

        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (succeeded) {
            refund.setStatus(PaymentStatus.REFUNDED.name());
            payment.setStatus(PaymentStatus.REFUNDED);
        } else {
            refund.setStatus("REFUND_FAILED");
            payment.setStatus(PaymentStatus.SUCCEEDED); // revert payment status
        }

        refund = refundRepository.save(refund);
        payment.setUpdatedAt(Instant.now());
        paymentRepository.save(payment);

        saveOutboxEvent(paymentId, payment.getOrderId(), succeeded ? "PaymentRefunded" : "PaymentRefundFailed", Map.of(
                "eventId", UUID.randomUUID().toString(),
                "refundId", refundId,
                "paymentId", paymentId,
                "amount", refund.getAmount().toString(),
                "status", refund.getStatus(),
                "reason", refund.getReason() != null ? refund.getReason() : ""
        ));

        return refund;
    }

    private void saveOutboxEvent(String aggregateId, String orderId, String eventType, Map<String, Object> payloadMap) {
        String eventPayload;
        try {
            eventPayload = objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event payload", e);
        }

        OutboxEntity outbox = new OutboxEntity(
                UUID.randomUUID().toString(),
                "PAYMENT",
                aggregateId,
                orderId,
                eventType,
                1,
                eventPayload
        );
        outboxRepository.save(outbox);
    }

    public record PaymentInitiationResult(PaymentEntity entity, boolean isDuplicate) {}
}

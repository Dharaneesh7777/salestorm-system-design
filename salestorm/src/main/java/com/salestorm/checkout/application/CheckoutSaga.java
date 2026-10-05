package com.salestorm.checkout.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salestorm.checkout.domain.saga.SagaStateEntity;
import com.salestorm.checkout.domain.saga.SagaStateRepository;
import com.salestorm.checkout.domain.saga.SagaStatus;
import com.salestorm.common.domain.event.EventBus;
import com.salestorm.common.domain.event.MessageEnvelope;
import com.salestorm.common.domain.event.OutboxWriter;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.domain.ReleaseReason;
import com.salestorm.payment.application.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CheckoutSaga {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);

    private final SagaStateRepository sagaRepository;
    private final EventBus eventBus;
    private final OutboxWriter outboxWriter;
    private final ReservationService reservationService;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public CheckoutSaga(
            SagaStateRepository sagaRepository,
            EventBus eventBus,
            OutboxWriter outboxWriter,
            ReservationService reservationService,
            PaymentService paymentService,
            ObjectMapper objectMapper
    ) {
        this.sagaRepository = sagaRepository;
        this.eventBus = eventBus;
        this.outboxWriter = outboxWriter;
        this.reservationService = reservationService;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SagaStateEntity startSaga(
            String orderId,
            String reservationId,
            Long customerId,
            Long productId,
            int quantity,
            BigDecimal amount,
            String currency
    ) {
        Optional<SagaStateEntity> existing = sagaRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }

        SagaStateEntity saga = new SagaStateEntity(
                orderId,
                orderId,
                reservationId,
                customerId,
                productId,
                quantity,
                amount,
                currency != null ? currency : "USD",
                SagaStatus.RESERVED,
                "ReservationCreated"
        );
        saga = sagaRepository.save(saga);
        log.info("CheckoutSaga started for order {} in RESERVED status", orderId);
        return saga;
    }

    @Transactional
    public void onPaymentInitiated(String orderId, String paymentId, String eventId) {
        Optional<SagaStateEntity> sagaOpt = sagaRepository.findByOrderId(orderId);
        if (sagaOpt.isEmpty()) {
            log.warn("Saga not found for order {} on PaymentInitiated", orderId);
            return;
        }

        SagaStateEntity saga = sagaOpt.get();
        if (saga.getStatus().isTerminal()) {
            return;
        }

        saga.setPaymentId(paymentId);
        saga.transitionTo(SagaStatus.PAYMENT_PENDING, "PaymentInitiated");
        sagaRepository.save(saga);

        // Send MarkOrderPaymentPending command via EventBus
        sendCommand("MarkOrderPaymentPending", orderId, Map.of(
                "orderId", orderId,
                "paymentId", paymentId != null ? paymentId : ""
        ));
    }

    @Transactional
    public void onPaymentSucceeded(String orderId, String paymentId, String reservationId, BigDecimal amount, String eventId) {
        Optional<SagaStateEntity> sagaOpt = sagaRepository.findByOrderId(orderId);
        if (sagaOpt.isEmpty()) {
            log.warn("Saga not found for order {} on PaymentSucceeded", orderId);
            return;
        }

        SagaStateEntity saga = sagaOpt.get();
        if (saga.getStatus().isTerminal() || saga.getStatus() == SagaStatus.PAID) {
            return;
        }

        if (paymentId != null) {
            saga.setPaymentId(paymentId);
        }
        if (reservationId != null) {
            saga.setReservationId(reservationId);
        }
        saga.transitionTo(SagaStatus.PAID, "PaymentSucceeded");
        sagaRepository.save(saga);

        log.info("CheckoutSaga moved to PAID for order {}. Dispatching ConfirmOrder command.", orderId);

        // Send ConfirmOrder command via EventBus
        sendConfirmOrderCommand(orderId, saga.getPaymentId(), saga.getReservationId(), saga.getAmount());
    }

    @Transactional
    public void onPaymentFailed(String orderId, String reason, String eventId) {
        Optional<SagaStateEntity> sagaOpt = sagaRepository.findByOrderId(orderId);
        if (sagaOpt.isEmpty()) {
            return;
        }

        SagaStateEntity saga = sagaOpt.get();
        if (saga.getStatus().isTerminal()) {
            return;
        }

        saga.fail("Payment failed: " + reason);
        sagaRepository.save(saga);

        log.info("CheckoutSaga failed for order {}: {}. Compensating by cancelling order.", orderId, reason);

        // Send CancelOrder command via EventBus
        sendCommand("CancelOrder", orderId, Map.of(
                "orderId", orderId,
                "reason", "Payment failed: " + reason
        ));

        // Compensate inventory if reservation exists
        if (saga.getReservationId() != null) {
            try {
                reservationService.release(saga.getReservationId(), ReleaseReason.PAYMENT_FAILED);
            } catch (Exception e) {
                log.warn("Failed to release reservation {} during payment failure compensation: {}", saga.getReservationId(), e.getMessage());
            }
        }
    }

    @Transactional
    public void onOrderConfirmed(String orderId, String eventId) {
        Optional<SagaStateEntity> sagaOpt = sagaRepository.findByOrderId(orderId);
        if (sagaOpt.isEmpty()) {
            return;
        }

        SagaStateEntity saga = sagaOpt.get();
        if (saga.getStatus() == SagaStatus.DONE) {
            return;
        }

        saga.transitionTo(SagaStatus.DONE, "OrderConfirmed");
        sagaRepository.save(saga);

        log.info("CheckoutSaga reached DONE for order {}", orderId);

        // Publish CheckoutCompleted event
        outboxWriter.write("SAGA", orderId, orderId, "CheckoutCompleted", Map.of(
                "orderId", orderId,
                "status", "DONE",
                "completedAt", Instant.now().toString()
        ), null);
    }

    @Transactional
    public void onReservationConfirmationRejected(String orderId, String reservationId, String paymentId, String reason) {
        Optional<SagaStateEntity> sagaOpt = sagaRepository.findByOrderId(orderId);
        if (sagaOpt.isEmpty()) {
            return;
        }

        SagaStateEntity saga = sagaOpt.get();
        saga.compensate("Reservation rejected: " + reason);
        sagaRepository.save(saga);

        log.warn("CheckoutSaga COMPENSATING for order {} due to rejected reservation. Initiating refund.", orderId);

        // Trigger refund
        String effectivePaymentId = paymentId != null ? paymentId : saga.getPaymentId();
        if (effectivePaymentId != null) {
            try {
                paymentService.refund(effectivePaymentId, "Reservation expired or unavailable during confirmation");
            } catch (Exception e) {
                log.error("Failed to execute refund for payment {} during reservation compensation: {}", effectivePaymentId, e.getMessage(), e);
            }
        }

        // Cancel order
        sendCommand("CancelOrder", orderId, Map.of(
                "orderId", orderId,
                "reason", "Reservation confirmation rejected"
        ));
    }

    @Transactional
    public void onCheckoutFailed(String orderId, String reservationId, String paymentId, String reason) {
        Optional<SagaStateEntity> sagaOpt = sagaRepository.findByOrderId(orderId);
        if (sagaOpt.isPresent()) {
            SagaStateEntity saga = sagaOpt.get();
            saga.fail("Checkout failed: " + reason);
            sagaRepository.save(saga);
        }

        log.warn("CheckoutFailed for order {}. Compensating reservation and payment.", orderId);

        // 1. Release reservation if present
        if (reservationId != null) {
            try {
                reservationService.release(reservationId, ReleaseReason.COMPENSATION);
            } catch (Exception e) {
                log.warn("Error releasing reservation {} on checkout failure: {}", reservationId, e.getMessage());
            }
        }

        // 2. Refund payment if present
        if (paymentId != null) {
            try {
                paymentService.refund(paymentId, "Checkout failed compensation: " + reason);
            } catch (Exception e) {
                log.warn("Error refunding payment {} on checkout failure: {}", paymentId, e.getMessage());
            }
        }
    }

    public void sendConfirmOrderCommand(String orderId, String paymentId, String reservationId, BigDecimal amount) {
        sendCommand("ConfirmOrder", orderId, Map.of(
                "orderId", orderId,
                "paymentId", paymentId != null ? paymentId : "",
                "reservationId", reservationId != null ? reservationId : "",
                "amount", amount != null ? amount.toString() : "0.00"
        ));
    }

    private void sendCommand(String commandType, String orderId, Map<String, Object> payloadMap) {
        String eventId = UUID.randomUUID().toString();
        String payloadJson = "";
        try {
            payloadJson = objectMapper.writeValueAsString(payloadMap);
        } catch (Exception ignored) {
        }

        MessageEnvelope envelope = new MessageEnvelope(
                eventId,
                commandType,
                1,
                Instant.now(),
                orderId,
                UUID.randomUUID().toString(),
                null,
                payloadJson
        );
        eventBus.publish(envelope);
    }
}

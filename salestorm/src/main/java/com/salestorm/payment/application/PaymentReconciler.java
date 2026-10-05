package com.salestorm.payment.application;

import com.salestorm.common.infrastructure.config.SaleProperties;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.domain.ReleaseReason;
import com.salestorm.payment.domain.GatewayResponse;
import com.salestorm.payment.domain.GatewayStatus;
import com.salestorm.payment.domain.PaymentGateway;
import com.salestorm.payment.domain.PaymentRepository;
import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Background reconciler scanning for TIMEOUT / UNKNOWN payments.
 * Queries gateway status and transitions payments to SUCCEEDED or FAILED.
 * After payment grace period, persistent NOT_FOUND / UNKNOWN payments are transitioned to FAILED.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);
    private static final int BATCH_SIZE = 50;

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentTransactionService transactionService;
    private final ReservationService reservationService;
    private final SaleProperties saleProperties;

    public PaymentReconciler(
            PaymentRepository paymentRepository,
            PaymentGateway paymentGateway,
            PaymentTransactionService transactionService,
            ReservationService reservationService,
            SaleProperties saleProperties
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.transactionService = transactionService;
        this.reservationService = reservationService;
        this.saleProperties = saleProperties;
    }

    @Scheduled(fixedDelay = 5000)
    public void reconcileTimeoutPayments() {
        List<PaymentEntity> timeoutPayments = paymentRepository.findTimeoutPayments(Instant.now(), BATCH_SIZE);
        if (!timeoutPayments.isEmpty()) {
            log.debug("Reconciling {} timeout payments", timeoutPayments.size());
        }

        for (PaymentEntity payment : timeoutPayments) {
            try {
                reconcileSinglePayment(payment);
            } catch (Exception e) {
                log.error("Error reconciling payment {}: {}", payment.getPaymentId(), e.getMessage(), e);
            }
        }
    }

    public void reconcileSinglePayment(PaymentEntity payment) {
        String paymentId = payment.getPaymentId();
        GatewayResponse response = paymentGateway.queryStatus(paymentId);

        log.info("Reconciliation query for payment {}: status={}", paymentId, response.status());

        if (response.status() == GatewayStatus.SUCCEEDED) {
            transactionService.completePaymentTransaction(paymentId, PaymentStatus.SUCCEEDED, response.gatewayRef());
            if (payment.getReservationId() != null && !payment.getReservationId().isBlank()) {
                try {
                    reservationService.confirm(payment.getReservationId());
                } catch (Exception e) {
                    log.error("Failed to confirm reservation {} during reconciliation: {}", payment.getReservationId(), e.getMessage());
                }
            }
        } else if (response.status() == GatewayStatus.DECLINED || response.status() == GatewayStatus.FAILED) {
            transactionService.completePaymentTransaction(paymentId, PaymentStatus.FAILED, response.gatewayRef());
            if (payment.getReservationId() != null && !payment.getReservationId().isBlank()) {
                try {
                    reservationService.release(payment.getReservationId(), ReleaseReason.PAYMENT_FAILED);
                } catch (Exception e) {
                    log.warn("Failed to release reservation {} during reconciliation: {}", payment.getReservationId(), e.getMessage());
                }
            }
        } else if (response.status() == GatewayStatus.NOT_FOUND || response.status() == GatewayStatus.UNKNOWN) {
            Instant graceCutoff = Instant.now().minusSeconds(saleProperties.getPaymentGraceSeconds());
            if (payment.getCreatedAt().isBefore(graceCutoff)) {
                log.warn("Payment {} remains unresolved past grace period ({}s). Marking as FAILED.",
                        paymentId, saleProperties.getPaymentGraceSeconds());
                transactionService.completePaymentTransaction(paymentId, PaymentStatus.FAILED, "EXPIRED_UNRESOLVED");
                if (payment.getReservationId() != null && !payment.getReservationId().isBlank()) {
                    try {
                        reservationService.release(payment.getReservationId(), ReleaseReason.PAYMENT_FAILED);
                    } catch (Exception e) {
                        log.warn("Failed to release reservation {} after grace expiry: {}", payment.getReservationId(), e.getMessage());
                    }
                }
            }
        }
    }
}

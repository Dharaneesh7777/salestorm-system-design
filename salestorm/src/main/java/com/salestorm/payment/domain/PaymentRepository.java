package com.salestorm.payment.domain;

import com.salestorm.payment.infrastructure.entity.PaymentEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain repository port for payment entities.
 */
public interface PaymentRepository {

    PaymentEntity save(PaymentEntity payment);

    Optional<PaymentEntity> findById(String paymentId);

    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentEntity> findByReservationId(String reservationId);

    List<PaymentEntity> findByStatus(PaymentStatus status);

    List<PaymentEntity> findTimeoutPayments(Instant beforeTime, int limit);

    int updateStatusConditional(String paymentId, PaymentStatus expectedStatus, PaymentStatus newStatus, String gatewayRef);
}

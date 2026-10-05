package com.salestorm.payment.domain;

import com.salestorm.payment.infrastructure.entity.RefundEntity;

import java.util.Optional;

/**
 * Domain repository port for refund entities.
 */
public interface RefundRepository {

    RefundEntity save(RefundEntity refund);

    Optional<RefundEntity> findById(String refundId);

    Optional<RefundEntity> findByPaymentId(String paymentId);

    int updateStatusConditional(String refundId, String expectedStatus, String newStatus);
}

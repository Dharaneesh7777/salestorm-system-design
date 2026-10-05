package com.salestorm.payment.infrastructure.persistence;

import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataJpaPaymentRepository extends JpaRepository<PaymentEntity, String> {

    Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentEntity> findByReservationId(String reservationId);

    List<PaymentEntity> findByStatus(PaymentStatus status);

    @Query("SELECT p FROM PaymentEntity p WHERE p.status = 'TIMEOUT' AND p.updatedAt < :beforeTime ORDER BY p.updatedAt ASC")
    List<PaymentEntity> findTimeoutPayments(@Param("beforeTime") Instant beforeTime, Pageable pageable);

    @Modifying
    @Query(value = "UPDATE PAYMENT SET status = :newStatus, gateway_ref = COALESCE(:gatewayRef, gateway_ref), updated_at = CURRENT_TIMESTAMP WHERE payment_id = :paymentId AND status = :expectedStatus", nativeQuery = true)
    int updateStatusConditional(
            @Param("paymentId") String paymentId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("gatewayRef") String gatewayRef
    );
}

package com.salestorm.payment.infrastructure.persistence;

import com.salestorm.payment.infrastructure.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataJpaRefundRepository extends JpaRepository<RefundEntity, String> {

    Optional<RefundEntity> findByPaymentId(String paymentId);

    @Modifying
    @Query(value = "UPDATE REFUND SET status = :newStatus WHERE refund_id = :refundId AND status = :expectedStatus", nativeQuery = true)
    int updateStatusConditional(
            @Param("refundId") String refundId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus
    );
}

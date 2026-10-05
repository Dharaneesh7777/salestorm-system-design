package com.salestorm.payment.infrastructure.persistence;

import com.salestorm.payment.domain.RefundRepository;
import com.salestorm.payment.infrastructure.entity.RefundEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class RefundRepositoryImpl implements RefundRepository {

    private final SpringDataJpaRefundRepository springDataRepository;

    public RefundRepositoryImpl(SpringDataJpaRefundRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public RefundEntity save(RefundEntity refund) {
        return springDataRepository.save(refund);
    }

    @Override
    public Optional<RefundEntity> findById(String refundId) {
        return springDataRepository.findById(refundId);
    }

    @Override
    public Optional<RefundEntity> findByPaymentId(String paymentId) {
        return springDataRepository.findByPaymentId(paymentId);
    }

    @Override
    public int updateStatusConditional(String refundId, String expectedStatus, String newStatus) {
        return springDataRepository.updateStatusConditional(refundId, expectedStatus, newStatus);
    }
}

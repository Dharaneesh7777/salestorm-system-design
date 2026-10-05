package com.salestorm.payment.infrastructure.persistence;

import com.salestorm.payment.domain.PaymentRepository;
import com.salestorm.payment.domain.PaymentStatus;
import com.salestorm.payment.infrastructure.entity.PaymentEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class PaymentRepositoryImpl implements PaymentRepository {

    private final SpringDataJpaPaymentRepository springDataRepository;

    public PaymentRepositoryImpl(SpringDataJpaPaymentRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public PaymentEntity save(PaymentEntity payment) {
        return springDataRepository.save(payment);
    }

    @Override
    public Optional<PaymentEntity> findById(String paymentId) {
        return springDataRepository.findById(paymentId);
    }

    @Override
    public Optional<PaymentEntity> findByIdempotencyKey(String idempotencyKey) {
        return springDataRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public Optional<PaymentEntity> findByReservationId(String reservationId) {
        return springDataRepository.findByReservationId(reservationId);
    }

    @Override
    public List<PaymentEntity> findByStatus(PaymentStatus status) {
        return springDataRepository.findByStatus(status);
    }

    @Override
    public List<PaymentEntity> findTimeoutPayments(Instant beforeTime, int limit) {
        return springDataRepository.findTimeoutPayments(beforeTime, PageRequest.of(0, limit));
    }

    @Override
    public int updateStatusConditional(String paymentId, PaymentStatus expectedStatus, PaymentStatus newStatus, String gatewayRef) {
        return springDataRepository.updateStatusConditional(
                paymentId,
                expectedStatus.name(),
                newStatus.name(),
                gatewayRef
        );
    }
}

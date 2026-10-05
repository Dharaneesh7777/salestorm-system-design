package com.salestorm.checkout.domain.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "SAGA_STATE")
public class SagaStateEntity {

    @Id
    @Column(name = "saga_id", length = 64, nullable = false)
    private String sagaId;

    @Column(name = "order_id", length = 64, nullable = false, unique = true)
    private String orderId;

    @Column(name = "reservation_id", length = 64)
    private String reservationId;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity = 1;

    @Column(name = "amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private SagaStatus status;

    @Column(name = "step", length = 64)
    private String step;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SagaStateEntity() {
    }

    public SagaStateEntity(
            String sagaId,
            String orderId,
            String reservationId,
            Long customerId,
            Long productId,
            int quantity,
            BigDecimal amount,
            String currency,
            SagaStatus status,
            String step
    ) {
        this.sagaId = Objects.requireNonNull(sagaId, "sagaId must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.reservationId = reservationId;
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.quantity = quantity > 0 ? quantity : 1;
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.currency = currency != null ? currency : "USD";
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.step = step;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getSagaId() {
        return sagaId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getReservationId() {
        return reservationId;
    }

    public void setReservationId(String reservationId) {
        this.reservationId = reservationId;
        this.updatedAt = Instant.now();
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
        this.updatedAt = Instant.now();
    }

    public Long getCustomerId() {
        return customerId;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public SagaStatus getStatus() {
        return status;
    }

    public void transitionTo(SagaStatus newStatus, String step) {
        this.status = newStatus;
        this.step = step;
        this.updatedAt = Instant.now();
    }

    public void fail(String reason) {
        this.status = SagaStatus.FAILED;
        this.failureReason = reason;
        this.step = "FAILED";
        this.updatedAt = Instant.now();
    }

    public void compensate(String reason) {
        this.status = SagaStatus.COMPENSATING;
        this.failureReason = reason;
        this.step = "COMPENSATING";
        this.updatedAt = Instant.now();
    }

    public String getStep() {
        return step;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

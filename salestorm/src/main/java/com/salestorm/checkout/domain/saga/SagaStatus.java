package com.salestorm.checkout.domain.saga;

public enum SagaStatus {
    STARTED,
    RESERVED,
    PAYMENT_PENDING,
    PAID,
    DONE,
    COMPENSATING,
    FAILED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED;
    }
}

package com.salestorm.payment.domain;

/**
 * Status lifecycle of a payment transaction.
 */
public enum PaymentStatus {
    INITIATED,
    PENDING,
    SUCCEEDED,
    SUCCESS,
    FAILED,
    TIMEOUT,
    REFUND_PENDING,
    REFUNDED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == SUCCESS || this == FAILED || this == REFUNDED;
    }
}

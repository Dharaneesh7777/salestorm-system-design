package com.salestorm.order.domain;

/**
 * Status lifecycle of a customer order.
 */
public enum OrderStatus {
    CREATED(0),
    PAYMENT_PENDING(1),
    PENDING_PAYMENT(1), // alias
    CONFIRMED(2),
    PROCESSING(3),
    SHIPPED(4),
    OUT_FOR_DELIVERY(5),
    DELIVERED(6),
    CANCELLED(99),
    FAILED(99),
    REFUNDED(99);

    private final int rank;

    OrderStatus(int rank) {
        this.rank = rank;
    }

    public int getRank() {
        return rank;
    }

    public boolean isCancellable() {
        return this == CREATED || this == PAYMENT_PENDING || this == PENDING_PAYMENT || this == CONFIRMED || this == PROCESSING;
    }

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == FAILED || this == REFUNDED;
    }
}

package com.salestorm.inventory.domain;

/**
 * Status lifecycle of an inventory reservation.
 */
public enum ReservationStatus {
    RESERVED,
    PAYMENT_PENDING,
    CONFIRMED,
    SOLD,
    RELEASED;

    public boolean isActive() {
        return this == RESERVED || this == PAYMENT_PENDING || this == CONFIRMED;
    }

    public boolean isReleasable() {
        return this == RESERVED || this == PAYMENT_PENDING;
    }

    public boolean isTerminal() {
        return this == SOLD || this == RELEASED;
    }
}

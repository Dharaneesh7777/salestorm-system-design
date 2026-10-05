package com.salestorm.inventory.domain;

/**
 * Reasons for releasing an active or held inventory reservation.
 */
public enum ReleaseReason {
    EXPIRED,
    PAYMENT_FAILED,
    CANCELLED,
    COMPENSATION
}

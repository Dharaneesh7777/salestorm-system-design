package com.salestorm.payment.domain;

/**
 * Status returned by external payment gateway adapters.
 */
public enum GatewayStatus {
    SUCCEEDED,
    DECLINED,
    TIMEOUT,
    UNKNOWN,
    FAILED,
    NOT_FOUND
}

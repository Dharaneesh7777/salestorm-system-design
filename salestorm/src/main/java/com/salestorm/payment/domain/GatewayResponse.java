package com.salestorm.payment.domain;

/**
 * Standardized response contract from payment gateway interactions.
 */
public record GatewayResponse(
        GatewayStatus status,
        String gatewayRef,
        String message,
        Integer httpStatus
) {
    public static GatewayResponse success(String gatewayRef) {
        return new GatewayResponse(GatewayStatus.SUCCEEDED, gatewayRef, "Payment succeeded", 200);
    }

    public static GatewayResponse declined(String reason) {
        return new GatewayResponse(GatewayStatus.DECLINED, null, reason != null ? reason : "Payment declined by issuing bank", 402);
    }

    public static GatewayResponse timeout(String message) {
        return new GatewayResponse(GatewayStatus.UNKNOWN, null, message != null ? message : "Gateway response timed out", 504);
    }

    public static GatewayResponse unknown(String message) {
        return new GatewayResponse(GatewayStatus.UNKNOWN, null, message != null ? message : "Transaction status unknown", 500);
    }

    public static GatewayResponse failed(String message, Integer httpStatus) {
        return new GatewayResponse(GatewayStatus.FAILED, null, message != null ? message : "Gateway call failed", httpStatus != null ? httpStatus : 500);
    }

    public static GatewayResponse notFound() {
        return new GatewayResponse(GatewayStatus.NOT_FOUND, null, "Transaction not found on gateway", 404);
    }

    public boolean isSuccessful() {
        return status == GatewayStatus.SUCCEEDED;
    }

    public boolean isDeclined() {
        return status == GatewayStatus.DECLINED;
    }

    public boolean isTimeoutOrUnknown() {
        return status == GatewayStatus.TIMEOUT || status == GatewayStatus.UNKNOWN;
    }
}

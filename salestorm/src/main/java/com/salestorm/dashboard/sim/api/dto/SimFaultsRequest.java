package com.salestorm.dashboard.sim.api.dto;

public record SimFaultsRequest(
        PaymentGatewayFaultMode paymentGatewayMode,
        Boolean orderServiceDown
) {
    public enum PaymentGatewayFaultMode {
        NORMAL,
        SLOW,
        DOWN,
        ALWAYS_DECLINE
    }
}

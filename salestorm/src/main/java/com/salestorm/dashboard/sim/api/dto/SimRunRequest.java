package com.salestorm.dashboard.sim.api.dto;

public record SimRunRequest(
        Integer users,
        Integer stock,
        Integer duplicatePercent,
        Integer paymentFailPercent,
        Integer orderServiceDownSeconds
) {
    public int effectiveUsers() {
        return users != null && users > 0 ? Math.min(users, 20000) : 10000;
    }
    public int effectiveStock() {
        return stock != null && stock > 0 ? stock : 100;
    }
    public int effectiveDuplicatePercent() {
        return duplicatePercent != null ? Math.max(0, Math.min(duplicatePercent, 100)) : 2;
    }
    public int effectivePaymentFailPercent() {
        return paymentFailPercent != null ? Math.max(0, Math.min(paymentFailPercent, 100)) : 5;
    }
    public int effectiveOrderServiceDownSeconds() {
        return orderServiceDownSeconds != null ? Math.max(0, orderServiceDownSeconds) : 0;
    }
}

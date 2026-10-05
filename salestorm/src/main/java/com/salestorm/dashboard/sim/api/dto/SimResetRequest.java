package com.salestorm.dashboard.sim.api.dto;

public record SimResetRequest(
        Integer stock
) {
    public int effectiveStock() {
        return stock != null && stock > 0 ? stock : 100;
    }
}

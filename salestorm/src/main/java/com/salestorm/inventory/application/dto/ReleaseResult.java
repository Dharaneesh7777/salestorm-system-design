package com.salestorm.inventory.application.dto;

public record ReleaseResult(
        boolean released,
        Long productId,
        String reservationId
) {}

package com.salestorm.checkout.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckoutBuyResult(
        String orderId,
        String reservationId,
        Long customerId,
        Long productId,
        int quantity,
        BigDecimal totalAmount,
        String currency,
        Instant expiresAt,
        String status
) {}

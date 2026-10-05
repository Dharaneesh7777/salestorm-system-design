package com.salestorm.checkout.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckoutBuyResponse(
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

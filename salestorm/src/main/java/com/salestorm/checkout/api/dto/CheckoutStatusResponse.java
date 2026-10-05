package com.salestorm.checkout.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckoutStatusResponse(
        String orderId,
        String sagaStatus,
        String orderStatus,
        String paymentStatus,
        String reservationId,
        BigDecimal totalAmount,
        String currency,
        String failureReason,
        Instant updatedAt
) {}

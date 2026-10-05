package com.salestorm.checkout.application.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CheckoutStatusResult(
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

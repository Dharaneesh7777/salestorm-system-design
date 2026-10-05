package com.salestorm.checkout.api.dto;

import java.math.BigDecimal;

public record CheckoutPayResponse(
        String orderId,
        String paymentId,
        String status,
        BigDecimal amount,
        String currency,
        String gatewayRef,
        String failureReason
) {}

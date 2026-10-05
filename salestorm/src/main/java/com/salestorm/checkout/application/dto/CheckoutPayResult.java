package com.salestorm.checkout.application.dto;

import java.math.BigDecimal;

public record CheckoutPayResult(
        String orderId,
        String paymentId,
        String status,
        BigDecimal amount,
        String currency,
        String gatewayRef,
        String failureReason
) {}

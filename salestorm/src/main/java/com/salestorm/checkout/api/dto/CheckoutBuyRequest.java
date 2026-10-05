package com.salestorm.checkout.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CheckoutBuyRequest(
        @NotNull(message = "productId is required")
        Long productId,

        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity,

        String couponCode
) {}

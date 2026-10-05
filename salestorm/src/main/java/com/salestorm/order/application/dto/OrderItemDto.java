package com.salestorm.order.application.dto;

import java.math.BigDecimal;

public record OrderItemDto(
        Long productId,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {}

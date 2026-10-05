package com.salestorm.order.application.dto;

import java.math.BigDecimal;
import java.util.List;

public record CreateOrderCommand(
        String orderId,
        Long customerId,
        String reservationId,
        BigDecimal totalAmount,
        String currency,
        List<OrderItemDto> items
) {}

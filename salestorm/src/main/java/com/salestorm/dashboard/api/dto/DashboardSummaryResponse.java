package com.salestorm.dashboard.api.dto;

import java.math.BigDecimal;
import java.util.Map;

public record DashboardSummaryResponse(
        Long productId,
        int totalStock,
        int available,
        int reserved,
        int sold,
        int redisGateCount,
        int drift,
        int activeReservations,
        Map<String, Long> ordersByStatus,
        Map<String, Long> paymentsByStatus,
        Map<String, Long> sagaByState,
        int outboxUnpublished,
        boolean invariantOk
) {}

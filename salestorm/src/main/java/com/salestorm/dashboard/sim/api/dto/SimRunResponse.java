package com.salestorm.dashboard.sim.api.dto;

import java.time.Instant;

public record SimRunResponse(
        String runId,
        int totalRequests,
        int initialStock,
        Instant startedAt
) {}

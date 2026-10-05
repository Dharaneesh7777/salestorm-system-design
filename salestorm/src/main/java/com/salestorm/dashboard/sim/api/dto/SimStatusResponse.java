package com.salestorm.dashboard.sim.api.dto;

import java.time.Instant;

public record SimStatusResponse(
        boolean running,
        Instant startedAt,
        long elapsedMs,
        int sent,
        int completed,
        int reserved,
        int soldOut409,
        int duplicatesTotal,
        int duplicatesReturnedSame,
        int rateLimited,
        int errors,
        long p50,
        long p95,
        long p99
) {}

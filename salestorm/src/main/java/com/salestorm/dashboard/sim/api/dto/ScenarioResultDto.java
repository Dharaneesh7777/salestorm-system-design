package com.salestorm.dashboard.sim.api.dto;

import java.util.List;

public record ScenarioResultDto(
        String name,
        List<CheckItemDto> checks,
        boolean overallPass
) {
    public record CheckItemDto(
            String label,
            String expected,
            String actual,
            boolean pass
    ) {}
}

package com.salestorm.dashboard.sim;

import com.salestorm.dashboard.sim.api.dto.ScenarioResultDto;
import com.salestorm.dashboard.sim.application.ScenarioRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ScenarioRunnerIntegrationTest {

    @Autowired
    private ScenarioRunner scenarioRunner;

    @Test
    @DisplayName("Run LAST_ITEM Scenario: Stock 1 vs 2 concurrent buyers strictly passes zero oversell")
    void testLastItemScenario() {
        ScenarioResultDto result = scenarioRunner.runScenario("LAST_ITEM");

        assertNotNull(result);
        assertEquals("LAST_ITEM", result.name());
        assertTrue(result.overallPass(), "LAST_ITEM scenario must pass all checks");
        assertFalse(result.checks().isEmpty());
    }

    @Test
    @DisplayName("Run FULL_10000 Scenario: 10,000 requests, 100 stock, idempotency and conservation pass")
    void testFull10000Scenario() {
        ScenarioResultDto result = scenarioRunner.runScenario("FULL_10000");

        assertNotNull(result);
        assertEquals("FULL_10000", result.name());
        assertTrue(result.overallPass(), "FULL_10000 scenario must pass all checks");
        assertTrue(result.checks().stream().allMatch(ScenarioResultDto.CheckItemDto::pass));
    }
}

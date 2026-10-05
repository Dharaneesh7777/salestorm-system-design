package com.salestorm.dashboard.sim.api;

import com.salestorm.dashboard.sim.api.dto.*;
import com.salestorm.dashboard.sim.application.ScenarioRunner;
import com.salestorm.dashboard.sim.application.SimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/sim")
@ConditionalOnProperty(name = "salestorm.demo.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Simulation & Fault Control", description = "High-concurrency load testing, scenario runner, fault injection, and state reset")
public class SimulationController {

    private final SimulationService simulationService;
    private final ScenarioRunner scenarioRunner;

    public SimulationController(SimulationService simulationService, ScenarioRunner scenarioRunner) {
        this.simulationService = simulationService;
        this.scenarioRunner = scenarioRunner;
    }

    @PostMapping("/run")
    @Operation(summary = "Start asynchronous flash sale simulation on background thread pool")
    public ResponseEntity<SimRunResponse> runSimulation(@RequestBody(required = false) SimRunRequest request) {
        SimRunRequest req = request != null ? request : new SimRunRequest(10000, 100, 2, 5, 0);
        return ResponseEntity.ok(simulationService.runSimulation(req));
    }

    @GetMapping("/status")
    @Operation(summary = "Get live simulation progress and latency percentiles (p50/p95/p99)")
    public ResponseEntity<SimStatusResponse> getStatus() {
        return ResponseEntity.ok(simulationService.getStatus());
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset all database tables and stock gate back to specified stock")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody(required = false) SimResetRequest request) {
        int stock = request != null ? request.effectiveStock() : 100;
        simulationService.resetData(stock);
        return ResponseEntity.ok(Map.of(
                "message", "Database and Gate successfully reset to " + stock + " units",
                "stock", stock
        ));
    }

    @PostMapping("/faults")
    @Operation(summary = "Inject faults: toggle payment gateway mode and order service status")
    public ResponseEntity<Map<String, Object>> applyFaults(@RequestBody SimFaultsRequest request) {
        simulationService.applyFaults(request);
        return ResponseEntity.ok(Map.of(
                "message", "Faults applied successfully",
                "paymentGatewayMode", request.paymentGatewayMode() != null ? request.paymentGatewayMode().name() : "UNCHANGED",
                "orderServiceDown", request.orderServiceDown() != null ? request.orderServiceDown() : "UNCHANGED"
        ));
    }

    @GetMapping("/scenarios/{name}/run")
    @Operation(summary = "Run preconfigured demo scenario (LAST_ITEM, FULL_10000, DUPLICATES, PAYMENT_FAILURES, ORDER_SERVICE_DOWN, GATEWAY_DOWN)")
    public ResponseEntity<ScenarioResultDto> runScenario(@PathVariable String name) {
        return ResponseEntity.ok(scenarioRunner.runScenario(name));
    }
}

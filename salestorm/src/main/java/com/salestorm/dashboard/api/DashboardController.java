package com.salestorm.dashboard.api;

import com.salestorm.dashboard.api.dto.*;
import com.salestorm.dashboard.application.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@ConditionalOnProperty(name = "salestorm.demo.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Dashboard (War Room)", description = "Read-only metrics, real-time SSE streams, entity explorers, and order timeline")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Get system summary metrics and invariant check")
    public ResponseEntity<DashboardSummaryResponse> getSummary() {
        return ResponseEntity.ok(dashboardService.getSummary());
    }

    @GetMapping("/reservations")
    @Operation(summary = "List latest inventory reservations")
    public ResponseEntity<List<ReservationItemDto>> getReservations(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(dashboardService.getReservations(status, limit));
    }

    @GetMapping("/orders")
    @Operation(summary = "List latest orders")
    public ResponseEntity<List<OrderItemDto>> getOrders(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(dashboardService.getOrders(limit));
    }

    @GetMapping("/payments")
    @Operation(summary = "List latest payments")
    public ResponseEntity<List<PaymentItemDto>> getPayments(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return ResponseEntity.ok(dashboardService.getPayments(limit));
    }

    @GetMapping("/events")
    @Operation(summary = "List latest outbox domain events")
    public ResponseEntity<List<EventLogItemDto>> getEvents(
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ResponseEntity.ok(dashboardService.getEvents(limit));
    }

    @GetMapping("/orders/{orderId}/timeline")
    @Operation(summary = "Get comprehensive order timeline with state history, payment, and reservation")
    public ResponseEntity<OrderTimelineResponse> getOrderTimeline(@PathVariable String orderId) {
        return dashboardService.getOrderTimeline(orderId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderId));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-Sent Events (SSE) stream pushing live summary every 1 second")
    public SseEmitter streamSummary() {
        return dashboardService.subscribeStream();
    }
}

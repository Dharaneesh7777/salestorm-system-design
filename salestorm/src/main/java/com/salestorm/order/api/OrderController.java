package com.salestorm.order.api;

import com.salestorm.order.api.dto.CancelOrderRequest;
import com.salestorm.order.api.dto.OrderResponseDto;
import com.salestorm.order.application.OrderService;
import com.salestorm.order.application.dto.OrderResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Order management and cancellation lifecycle endpoints")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get Order Details", description = "Retrieves order status, line items, and complete status audit history")
    public ResponseEntity<OrderResponseDto> getOrder(@PathVariable("id") String orderId) {
        return orderService.getOrderById(orderId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + orderId));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel Order", description = "Cancels an order before shipment. Cancellation after SHIPPED will be rejected.")
    public ResponseEntity<OrderResponseDto> cancelOrder(
            @PathVariable("id") String orderId,
            @RequestBody(required = false) CancelOrderRequest request
    ) {
        String reason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason()
                : "Customer requested cancellation";

        String eventId = UUID.randomUUID().toString();
        OrderResult result = orderService.onCancelOrder(orderId, reason, eventId);

        return ResponseEntity.ok(toResponse(result));
    }

    private OrderResponseDto toResponse(OrderResult result) {
        var itemResponses = result.items().stream()
                .map(item -> new OrderResponseDto.OrderItemResponseDto(
                        item.productId(),
                        item.quantity(),
                        item.unitPrice(),
                        item.lineTotal()
                ))
                .toList();

        var historyResponses = result.history().stream()
                .map(h -> new OrderResponseDto.OrderStatusHistoryResponseDto(
                        h.oldStatus(),
                        h.newStatus(),
                        h.reason(),
                        h.eventId(),
                        h.changedAt()
                ))
                .toList();

        return new OrderResponseDto(
                result.orderId(),
                result.customerId(),
                result.reservationId(),
                result.status().name(),
                result.totalAmount(),
                result.currency(),
                result.cancelReason(),
                result.createdAt(),
                itemResponses,
                historyResponses
        );
    }
}

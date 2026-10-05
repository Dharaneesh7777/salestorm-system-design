package com.salestorm.checkout.api;

import com.salestorm.checkout.api.dto.CheckoutBuyRequest;
import com.salestorm.checkout.api.dto.CheckoutBuyResponse;
import com.salestorm.checkout.api.dto.CheckoutPayRequest;
import com.salestorm.checkout.api.dto.CheckoutPayResponse;
import com.salestorm.checkout.api.dto.CheckoutStatusResponse;
import com.salestorm.checkout.application.CheckoutFacade;
import com.salestorm.checkout.application.dto.CheckoutBuyResult;
import com.salestorm.checkout.application.dto.CheckoutPayResult;
import com.salestorm.checkout.application.dto.CheckoutStatusResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/checkout")
@Tag(name = "Checkout", description = "Flash sale purchase, payment orchestration and saga state tracking")
public class CheckoutController {

    private final CheckoutFacade checkoutFacade;

    public CheckoutController(CheckoutFacade checkoutFacade) {
        this.checkoutFacade = checkoutFacade;
    }

    @PostMapping("/buy")
    @Operation(summary = "Buy Now - initiates flash sale stock reservation, pricing, and saga orchestration")
    public ResponseEntity<CheckoutBuyResponse> buy(
            @Parameter(description = "Customer ID header (simple auth stub)", required = true)
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @Parameter(description = "Unique idempotency key", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutBuyRequest request
    ) {
        if (customerId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Required header 'X-Customer-Id' is missing");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required header 'Idempotency-Key' is missing or empty");
        }

        int quantity = (request.quantity() != null && request.quantity() > 0) ? request.quantity() : 1;
        CheckoutBuyResult result = checkoutFacade.buy(customerId, request.productId(), quantity, request.couponCode(), idempotencyKey.trim());

        CheckoutBuyResponse response = new CheckoutBuyResponse(
                result.orderId(),
                result.reservationId(),
                result.customerId(),
                result.productId(),
                result.quantity(),
                result.totalAmount(),
                result.currency(),
                result.expiresAt(),
                result.status()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/pay")
    @Operation(summary = "Pay - executes payment for an order and advances saga orchestration")
    public ResponseEntity<CheckoutPayResponse> pay(
            @Parameter(description = "Customer ID header", required = true)
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId,
            @Parameter(description = "Unique idempotency key", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutPayRequest request
    ) {
        if (customerId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Required header 'X-Customer-Id' is missing");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required header 'Idempotency-Key' is missing or empty");
        }

        CheckoutPayResult result = checkoutFacade.pay(customerId, request.orderId(), request.amount(), idempotencyKey.trim());

        CheckoutPayResponse response = new CheckoutPayResponse(
                result.orderId(),
                result.paymentId(),
                result.status(),
                result.amount(),
                result.currency(),
                result.gatewayRef(),
                result.failureReason()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{orderId}/status")
    @Operation(summary = "Get Checkout Status - queries saga and order status (resilient to service outages)")
    public ResponseEntity<CheckoutStatusResponse> getStatus(
            @PathVariable("orderId") String orderId
    ) {
        CheckoutStatusResult result = checkoutFacade.getStatus(orderId);

        CheckoutStatusResponse response = new CheckoutStatusResponse(
                result.orderId(),
                result.sagaStatus(),
                result.orderStatus(),
                result.paymentStatus(),
                result.reservationId(),
                result.totalAmount(),
                result.currency(),
                result.failureReason(),
                result.updatedAt()
        );
        return ResponseEntity.ok(response);
    }
}

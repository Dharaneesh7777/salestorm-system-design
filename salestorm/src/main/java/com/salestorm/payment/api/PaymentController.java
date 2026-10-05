package com.salestorm.payment.api;

import com.salestorm.payment.api.dto.CreatePaymentRequest;
import com.salestorm.payment.api.dto.CreateRefundRequest;
import com.salestorm.payment.api.dto.PaymentResponse;
import com.salestorm.payment.api.dto.RefundResponse;
import com.salestorm.payment.application.PaymentService;
import com.salestorm.payment.application.dto.PaymentResult;
import com.salestorm.payment.application.dto.RefundResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "High-throughput payment processing and refund endpoints")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(summary = "Process Payment", description = "Executes payment authorization and capture against the resilient payment gateway")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment processed successfully", content = @Content(schema = @Schema(implementation = PaymentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request payload or missing Idempotency-Key"),
            @ApiResponse(responseCode = "409", description = "Idempotency key conflict"),
            @ApiResponse(
                    responseCode = "503",
                    description = "Circuit breaker open / Gateway unavailable",
                    headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying", schema = @Schema(type = "integer", example = "30"))
            )
    })
    public ResponseEntity<PaymentResponse> processPayment(
            @Parameter(description = "Unique client-generated idempotency key (UUID)", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required header 'Idempotency-Key' is missing or empty");
        }

        PaymentResult result = paymentService.pay(
                request.reservationId(),
                request.orderId(),
                request.amount(),
                request.currency(),
                idempotencyKey.trim()
        );

        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{paymentId}/refund")
    @Operation(summary = "Refund Payment", description = "Processes a refund for a previously succeeded payment (exactly once per payment)")
    public ResponseEntity<RefundResponse> refundPayment(
            @PathVariable("paymentId") String paymentId,
            @Valid @RequestBody(required = false) CreateRefundRequest request
    ) {
        var amount = request != null ? request.amount() : null;
        var reason = request != null ? request.reason() : "Customer requested refund";

        RefundResult result = paymentService.refund(paymentId, amount, reason);

        RefundResponse response = new RefundResponse(
                result.refundId(),
                result.paymentId(),
                result.amount(),
                result.status(),
                result.reason(),
                result.createdAt()
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get Payment Status", description = "Retrieves payment details by payment ID")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable("paymentId") String paymentId) {
        return paymentService.getPaymentById(paymentId)
                .map(res -> ResponseEntity.ok(toResponse(res)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found: " + paymentId));
    }

    private PaymentResponse toResponse(PaymentResult result) {
        return new PaymentResponse(
                result.paymentId(),
                result.orderId(),
                result.reservationId(),
                result.amount(),
                result.currency(),
                result.status().name(),
                result.gatewayRef(),
                result.idempotencyKey(),
                result.createdAt(),
                result.isDuplicate()
        );
    }
}

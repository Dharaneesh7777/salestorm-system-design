package com.salestorm.inventory.api;

import com.salestorm.common.api.ApiError;
import com.salestorm.inventory.api.dto.CreateReservationRequest;
import com.salestorm.inventory.api.dto.ReservationResponse;
import com.salestorm.inventory.application.ReservationService;
import com.salestorm.inventory.application.dto.ReservationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "Inventory Reservation", description = "Endpoints for reserving flash sale stock with high concurrency and idempotency")
public class ReservationController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @Operation(
            summary = "Create an inventory reservation",
            description = "Reserves one unit of flash sale stock for a customer. Requires an Idempotency-Key header."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stock successfully reserved",
                    content = @Content(schema = @Schema(implementation = ReservationResponse.class))),
            @ApiResponse(responseCode = "200", description = "Idempotent duplicate request returned same reservation",
                    content = @Content(schema = @Schema(implementation = ReservationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request payload or missing Idempotency-Key",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Sold out, payload conflict, or active reservation already exists",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "Stock temporarily unavailable / rate limited",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ReservationResponse> createReservation(
            @Parameter(
                    name = IDEMPOTENCY_KEY_HEADER,
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Unique client request key for idempotency",
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Required header 'Idempotency-Key' is missing or empty");
        }

        ReservationResult result = reservationService.reserve(
                request.getCustomerId(),
                request.getProductId(),
                idempotencyKey.trim()
        );

        ReservationResponse response = new ReservationResponse(
                result.reservationId(),
                result.productId(),
                result.customerId(),
                result.quantity(),
                result.status(),
                result.expiresAt(),
                result.idempotencyKey()
        );

        if (result.isDuplicate()) {
            return ResponseEntity.ok(response);
        }

        URI location = URI.create("/api/v1/reservations/" + result.reservationId());
        return ResponseEntity.created(location).body(response);
    }
}

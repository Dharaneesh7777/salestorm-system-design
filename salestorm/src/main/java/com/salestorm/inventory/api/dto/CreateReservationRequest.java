package com.salestorm.inventory.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "Request payload to reserve flash sale stock")
public class CreateReservationRequest {

    @NotNull(message = "customerId is required")
    @Positive(message = "customerId must be positive")
    @Schema(description = "Unique ID of the purchasing customer", example = "101")
    private Long customerId;

    @NotNull(message = "productId is required")
    @Positive(message = "productId must be positive")
    @Schema(description = "Unique ID of the flash sale product", example = "1")
    private Long productId;

    public CreateReservationRequest() {
    }

    public CreateReservationRequest(Long customerId, Long productId) {
        this.customerId = customerId;
        this.productId = productId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }
}

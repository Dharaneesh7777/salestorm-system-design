package com.salestorm.order.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Request payload for cancelling an order")
public record CancelOrderRequest(
        @Schema(description = "Reason for cancellation", example = "Customer changed mind")
        String reason
) {}

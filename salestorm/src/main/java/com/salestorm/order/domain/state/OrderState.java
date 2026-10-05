package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

/**
 * State pattern interface for customer order lifecycle transitions.
 * Enforces legal state progression, prevents cancellation after SHIPPED, and handles monotonic/idempotent replays.
 */
public interface OrderState {

    OrderStatus getStatus();

    OrderState initiatePayment();

    OrderState confirm();

    OrderState process();

    OrderState ship();

    OrderState outForDelivery();

    OrderState deliver();

    OrderState cancel(String reason);

    static OrderState from(OrderStatus status) {
        return switch (status) {
            case CREATED -> new CreatedState();
            case PAYMENT_PENDING, PENDING_PAYMENT -> new PaymentPendingState();
            case CONFIRMED -> new ConfirmedState();
            case PROCESSING -> new ProcessingState();
            case SHIPPED -> new ShippedState();
            case OUT_FOR_DELIVERY -> new OutForDeliveryState();
            case DELIVERED -> new DeliveredState();
            case CANCELLED, FAILED, REFUNDED -> new CancelledState();
        };
    }
}

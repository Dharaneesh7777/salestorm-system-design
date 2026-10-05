package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class ConfirmedState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.CONFIRMED;
    }

    @Override
    public OrderState initiatePayment() {
        return this; // Monotonic replay
    }

    @Override
    public OrderState confirm() {
        return this; // Idempotent replay
    }

    @Override
    public OrderState process() {
        return new ProcessingState();
    }

    @Override
    public OrderState ship() {
        return new ShippedState();
    }

    @Override
    public OrderState outForDelivery() {
        return new OutForDeliveryState();
    }

    @Override
    public OrderState deliver() {
        return new DeliveredState();
    }

    @Override
    public OrderState cancel(String reason) {
        return new CancelledState();
    }
}

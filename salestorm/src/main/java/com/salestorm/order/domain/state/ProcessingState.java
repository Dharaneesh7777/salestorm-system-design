package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class ProcessingState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.PROCESSING;
    }

    @Override
    public OrderState initiatePayment() {
        return this; // Monotonic
    }

    @Override
    public OrderState confirm() {
        return this; // Monotonic
    }

    @Override
    public OrderState process() {
        return this; // Idempotent
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

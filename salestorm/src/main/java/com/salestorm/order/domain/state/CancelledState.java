package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class CancelledState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.CANCELLED;
    }

    @Override
    public OrderState initiatePayment() {
        return this; // Monotonic / terminal
    }

    @Override
    public OrderState confirm() {
        return this; // Monotonic / terminal
    }

    @Override
    public OrderState process() {
        return this;
    }

    @Override
    public OrderState ship() {
        return this;
    }

    @Override
    public OrderState outForDelivery() {
        return this;
    }

    @Override
    public OrderState deliver() {
        return this;
    }

    @Override
    public OrderState cancel(String reason) {
        return this; // Idempotent
    }
}

package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class ShippedState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.SHIPPED;
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
        return this; // Monotonic
    }

    @Override
    public OrderState ship() {
        return this; // Idempotent
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
        throw new IllegalStateException("Invalid state transition: Cannot cancel an order that has already SHIPPED");
    }
}

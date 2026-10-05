package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class DeliveredState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.DELIVERED;
    }

    @Override
    public OrderState initiatePayment() {
        return this;
    }

    @Override
    public OrderState confirm() {
        return this;
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
        throw new IllegalStateException("Invalid state transition: Cannot cancel an order that is already DELIVERED");
    }
}

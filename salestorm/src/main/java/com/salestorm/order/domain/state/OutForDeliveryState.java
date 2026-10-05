package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class OutForDeliveryState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.OUT_FOR_DELIVERY;
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
        return new DeliveredState();
    }

    @Override
    public OrderState cancel(String reason) {
        throw new IllegalStateException("Invalid state transition: Cannot cancel an order that is OUT_FOR_DELIVERY");
    }
}

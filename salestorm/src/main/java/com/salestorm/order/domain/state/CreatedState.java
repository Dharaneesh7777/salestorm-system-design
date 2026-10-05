package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class CreatedState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.CREATED;
    }

    @Override
    public OrderState initiatePayment() {
        return new PaymentPendingState();
    }

    @Override
    public OrderState confirm() {
        // Direct confirmation allowed (e.g. out-of-order event delivery)
        return new ConfirmedState();
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

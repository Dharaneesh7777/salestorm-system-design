package com.salestorm.order.domain.state;

import com.salestorm.order.domain.OrderStatus;

public class PaymentPendingState implements OrderState {

    @Override
    public OrderStatus getStatus() {
        return OrderStatus.PAYMENT_PENDING;
    }

    @Override
    public OrderState initiatePayment() {
        return this; // Idempotent replay
    }

    @Override
    public OrderState confirm() {
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

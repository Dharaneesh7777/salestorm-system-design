package com.salestorm.inventory.domain.state;

import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.ReservationStatus;

public class PaymentPendingState implements ReservationState {

    @Override
    public ReservationStatus getStatus() {
        return ReservationStatus.PAYMENT_PENDING;
    }

    @Override
    public ReservationState initiatePayment() {
        // Idempotent retry on initiating payment while pending
        return this;
    }

    @Override
    public ReservationState confirmPayment() {
        return new ConfirmedState();
    }

    @Override
    public ReservationState finalizeSale() {
        throw new InvalidStateTransitionException(getStatus(), "finalizeSale");
    }

    @Override
    public ReservationState release(String reason) {
        return new ReleasedState();
    }
}

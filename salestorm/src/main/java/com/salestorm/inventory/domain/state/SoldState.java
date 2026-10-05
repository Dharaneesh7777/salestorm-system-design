package com.salestorm.inventory.domain.state;

import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.ReservationStatus;

public class SoldState implements ReservationState {

    @Override
    public ReservationStatus getStatus() {
        return ReservationStatus.SOLD;
    }

    @Override
    public ReservationState initiatePayment() {
        throw new InvalidStateTransitionException(getStatus(), "initiatePayment");
    }

    @Override
    public ReservationState confirmPayment() {
        throw new InvalidStateTransitionException(getStatus(), "confirmPayment");
    }

    @Override
    public ReservationState finalizeSale() {
        // Idempotent
        return this;
    }

    @Override
    public ReservationState release(String reason) {
        throw new InvalidStateTransitionException(getStatus(), "release");
    }
}

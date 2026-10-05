package com.salestorm.inventory.domain.state;

import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.ReservationStatus;

public class ReleasedState implements ReservationState {

    @Override
    public ReservationStatus getStatus() {
        return ReservationStatus.RELEASED;
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
        throw new InvalidStateTransitionException(getStatus(), "finalizeSale");
    }

    @Override
    public ReservationState release(String reason) {
        // Idempotent
        return this;
    }
}

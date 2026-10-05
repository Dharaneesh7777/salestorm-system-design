package com.salestorm.inventory.domain.state;

import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.ReservationStatus;

public class ReservedState implements ReservationState {

    @Override
    public ReservationStatus getStatus() {
        return ReservationStatus.RESERVED;
    }

    @Override
    public ReservationState initiatePayment() {
        return new PaymentPendingState();
    }

    @Override
    public ReservationState confirmPayment() {
        // Direct confirmation if payment is immediate
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

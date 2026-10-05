package com.salestorm.inventory.domain.state;

import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.ReservationStatus;

public class ConfirmedState implements ReservationState {

    @Override
    public ReservationStatus getStatus() {
        return ReservationStatus.CONFIRMED;
    }

    @Override
    public ReservationState initiatePayment() {
        throw new InvalidStateTransitionException(getStatus(), "initiatePayment");
    }

    @Override
    public ReservationState confirmPayment() {
        // Idempotent replay
        return this;
    }

    @Override
    public ReservationState finalizeSale() {
        return new SoldState();
    }

    @Override
    public ReservationState release(String reason) {
        // Confirmed reservation can only be released if compensation/order failure occurs
        return new ReleasedState();
    }
}

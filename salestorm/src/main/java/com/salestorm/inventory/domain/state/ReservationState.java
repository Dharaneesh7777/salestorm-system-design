package com.salestorm.inventory.domain.state;

import com.salestorm.inventory.domain.ReservationStatus;

/**
 * State interface for the Inventory Reservation lifecycle.
 */
public interface ReservationState {

    ReservationStatus getStatus();

    ReservationState initiatePayment();

    ReservationState confirmPayment();

    ReservationState finalizeSale();

    ReservationState release(String reason);

    static ReservationState from(ReservationStatus status) {
        return switch (status) {
            case RESERVED -> new ReservedState();
            case PAYMENT_PENDING -> new PaymentPendingState();
            case CONFIRMED -> new ConfirmedState();
            case SOLD -> new SoldState();
            case RELEASED -> new ReleasedState();
        };
    }
}

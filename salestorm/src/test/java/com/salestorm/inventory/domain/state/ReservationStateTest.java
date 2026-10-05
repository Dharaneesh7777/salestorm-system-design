package com.salestorm.inventory.domain.state;

import com.salestorm.inventory.domain.InvalidStateTransitionException;
import com.salestorm.inventory.domain.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReservationStateTest {

    @Test
    @DisplayName("Verify normal happy path state transitions: RESERVED -> PAYMENT_PENDING -> CONFIRMED -> SOLD")
    void testHappyPathTransitions() {
        ReservationState state = ReservationState.from(ReservationStatus.RESERVED);
        assertEquals(ReservationStatus.RESERVED, state.getStatus());

        state = state.initiatePayment();
        assertEquals(ReservationStatus.PAYMENT_PENDING, state.getStatus());

        state = state.confirmPayment();
        assertEquals(ReservationStatus.CONFIRMED, state.getStatus());

        state = state.finalizeSale();
        assertEquals(ReservationStatus.SOLD, state.getStatus());
    }

    @Test
    @DisplayName("Verify release transitions from RESERVED and PAYMENT_PENDING")
    void testReleaseTransitions() {
        ReservationState reserved = new ReservedState();
        ReservationState releasedFromReserved = reserved.release("TIMEOUT");
        assertEquals(ReservationStatus.RELEASED, releasedFromReserved.getStatus());

        ReservationState pending = new PaymentPendingState();
        ReservationState releasedFromPending = pending.release("PAYMENT_FAILED");
        assertEquals(ReservationStatus.RELEASED, releasedFromPending.getStatus());
    }

    @Test
    @DisplayName("Verify illegal transitions throw InvalidStateTransitionException")
    void testIllegalTransitions() {
        ReservationState reserved = new ReservedState();
        assertThrows(InvalidStateTransitionException.class, reserved::finalizeSale);

        ReservationState sold = new SoldState();
        assertThrows(InvalidStateTransitionException.class, sold::initiatePayment);
        assertThrows(InvalidStateTransitionException.class, () -> sold.release("CANCELLED"));

        ReservationState released = new ReleasedState();
        assertThrows(InvalidStateTransitionException.class, released::initiatePayment);
        assertThrows(InvalidStateTransitionException.class, released::finalizeSale);
    }
}

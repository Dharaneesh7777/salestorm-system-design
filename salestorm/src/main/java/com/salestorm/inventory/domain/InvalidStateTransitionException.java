package com.salestorm.inventory.domain;

/**
 * Thrown when an illegal state transition is attempted on an inventory reservation.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final ReservationStatus currentStatus;
    private final String attemptedAction;

    public InvalidStateTransitionException(ReservationStatus currentStatus, String attemptedAction) {
        super(String.format("Invalid state transition: Cannot perform action '%s' when reservation is in status '%s'",
                attemptedAction, currentStatus));
        this.currentStatus = currentStatus;
        this.attemptedAction = attemptedAction;
    }

    public ReservationStatus getCurrentStatus() {
        return currentStatus;
    }

    public String getAttemptedAction() {
        return attemptedAction;
    }
}

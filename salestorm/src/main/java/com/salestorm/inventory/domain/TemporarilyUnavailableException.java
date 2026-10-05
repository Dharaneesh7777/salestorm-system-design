package com.salestorm.inventory.domain;

public class TemporarilyUnavailableException extends RuntimeException {
    public TemporarilyUnavailableException(String message) {
        super(message);
    }
}

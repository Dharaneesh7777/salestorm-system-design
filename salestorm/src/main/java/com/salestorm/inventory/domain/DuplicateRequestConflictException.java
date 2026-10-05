package com.salestorm.inventory.domain;

public class DuplicateRequestConflictException extends RuntimeException {
    public DuplicateRequestConflictException(String message) {
        super(message);
    }
}

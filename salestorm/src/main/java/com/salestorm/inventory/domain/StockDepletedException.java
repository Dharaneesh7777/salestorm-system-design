package com.salestorm.inventory.domain;

public class StockDepletedException extends RuntimeException {
    public StockDepletedException(String message) {
        super(message);
    }
}

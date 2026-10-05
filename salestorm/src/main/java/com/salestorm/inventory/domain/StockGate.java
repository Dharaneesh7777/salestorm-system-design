package com.salestorm.inventory.domain;

/**
 * High-concurrency gatekeeper for preliminary stock validation and shedding excess traffic.
 */
public interface StockGate {

    /**
     * Attempts to acquire one stock unit from the gate.
     * @param productId the ID of the product
     * @return true if acquired; false if stock counter is at or below 0
     */
    boolean tryAcquire(Long productId);

    /**
     * Releases one stock unit back to the gate (compensation / cancellation / expiry).
     * @param productId the ID of the product
     */
    void release(Long productId);

    /**
     * Resets or seeds the gate counter for a product.
     * @param productId the ID of the product
     * @param count the available count
     */
    void reset(Long productId, int count);

    /**
     * Returns current counter value in the gate.
     * @param productId the ID of the product
     * @return current count
     */
    int getAvailable(Long productId);
}

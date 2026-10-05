package com.salestorm.inventory.domain;

import com.salestorm.inventory.infrastructure.entity.InventoryEntity;

import java.util.Optional;

/**
 * Domain repository port for inventory stock operations.
 */
public interface InventoryRepository {

    /**
     * Atomically decrements available stock and increments reserved stock if available stock > 0.
     * @param productId the ID of the product
     * @return number of rows updated (1 if successful, 0 if out of stock)
     */
    int reserveOne(Long productId);

    /**
     * Atomically increments available stock and decrements reserved stock if reserved stock > 0.
     * @param productId the ID of the product
     * @return number of rows updated
     */
    int releaseOne(Long productId);

    /**
     * Atomically decrements reserved stock and increments sold stock if reserved stock > 0.
     * @param productId the ID of the product
     * @return number of rows updated
     */
    int sellOne(Long productId);

    /**
     * Finds inventory entity by product ID.
     */
    Optional<InventoryEntity> findByProductId(Long productId);

    /**
     * Finds all inventory records.
     */
    java.util.List<InventoryEntity> findAll();

    /**
     * Saves or updates inventory entity.
     */
    InventoryEntity save(InventoryEntity inventory);
}

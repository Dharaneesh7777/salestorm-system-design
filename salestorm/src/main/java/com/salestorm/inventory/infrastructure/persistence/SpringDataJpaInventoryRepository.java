package com.salestorm.inventory.infrastructure.persistence;

import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataJpaInventoryRepository extends JpaRepository<InventoryEntity, Long> {

    Optional<InventoryEntity> findByProductId(Long productId);

    @Modifying
    @Query(value = "UPDATE INVENTORY SET available_quantity = available_quantity - 1, reserved_quantity = reserved_quantity + 1, updated_at = CURRENT_TIMESTAMP WHERE product_id = :productId AND available_quantity > 0", nativeQuery = true)
    int reserveOne(@Param("productId") Long productId);

    @Modifying
    @Query(value = "UPDATE INVENTORY SET available_quantity = available_quantity + 1, reserved_quantity = reserved_quantity - 1, updated_at = CURRENT_TIMESTAMP WHERE product_id = :productId AND reserved_quantity > 0", nativeQuery = true)
    int releaseOne(@Param("productId") Long productId);

    @Modifying
    @Query(value = "UPDATE INVENTORY SET reserved_quantity = reserved_quantity - 1, sold_quantity = sold_quantity + 1, updated_at = CURRENT_TIMESTAMP WHERE product_id = :productId AND reserved_quantity > 0", nativeQuery = true)
    int sellOne(@Param("productId") Long productId);
}

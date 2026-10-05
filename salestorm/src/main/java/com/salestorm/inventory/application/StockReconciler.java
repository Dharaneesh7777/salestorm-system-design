package com.salestorm.inventory.application;

import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Background reconciler periodically reconciling in-memory / Redis StockGate counters against MySQL truth.
 * Rule: Set gate counter to MySQL available if gate is higher; NEVER raise above DB truth.
 */
@Component
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    private final InventoryRepository inventoryRepository;
    private final StockGate stockGate;

    public StockReconciler(InventoryRepository inventoryRepository, StockGate stockGate) {
        this.inventoryRepository = inventoryRepository;
        this.stockGate = stockGate;
    }

    @Scheduled(fixedDelay = 30000)
    public void reconcileStock() {
        List<InventoryEntity> inventories = inventoryRepository.findAll();
        for (InventoryEntity inventory : inventories) {
            Long productId = inventory.getProductId();
            int dbAvailable = inventory.getAvailableQuantity();
            int gateAvailable = stockGate.getAvailable(productId);

            if (gateAvailable > dbAvailable) {
                log.warn("Stock divergence detected for product {}: gate count ({}) > DB available ({}). Lowering gate counter to match DB.",
                        productId, gateAvailable, dbAvailable);
                stockGate.reset(productId, dbAvailable);
            }
        }
    }
}

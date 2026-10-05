package com.salestorm.dashboard.application;

import com.salestorm.dashboard.api.dto.DashboardSummaryResponse;
import com.salestorm.inventory.domain.InventoryRepository;
import com.salestorm.inventory.domain.StockGate;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    private InventoryRepository inventoryRepository;
    private StockGate stockGate;
    private EntityManager entityManager;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        inventoryRepository = mock(InventoryRepository.class);
        stockGate = mock(StockGate.class);
        entityManager = mock(EntityManager.class);

        dashboardService = new DashboardService(inventoryRepository, stockGate);
        ReflectionTestUtils.setField(dashboardService, "entityManager", entityManager);
    }

    @Test
    @DisplayName("Verify Dashboard Summary computes stock conservation, drift, and invariantOk == true")
    void testSummaryComputesValidInvariant() {
        InventoryEntity inv = new InventoryEntity(1L, 100, 70, 20, 10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));
        when(stockGate.getAvailable(1L)).thenReturn(70);

        Query mockQuery = mock(Query.class);
        when(entityManager.createQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.emptyList());
        when(mockQuery.getSingleResult()).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertNotNull(summary);
        assertEquals(1L, summary.productId());
        assertEquals(100, summary.totalStock());
        assertEquals(70, summary.available());
        assertEquals(20, summary.reserved());
        assertEquals(10, summary.sold());
        assertEquals(70, summary.redisGateCount());
        assertEquals(0, summary.drift()); // 70 - 70 = 0
        assertTrue(summary.invariantOk(), "70 + 20 + 10 == 100 => invariant must be true");
    }

    @Test
    @DisplayName("Verify Dashboard Summary flags invariantOk == false on negative quantities or sum mismatch")
    void testSummaryFlagsBrokenInvariant() {
        // Mismatch: 50 + 20 + 10 = 80 != 100
        InventoryEntity inv = new InventoryEntity(1L, 100, 50, 20, 10);
        when(inventoryRepository.findByProductId(1L)).thenReturn(Optional.of(inv));
        when(stockGate.getAvailable(1L)).thenReturn(60);

        Query mockQuery = mock(Query.class);
        when(entityManager.createQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.getResultList()).thenReturn(Collections.emptyList());
        when(mockQuery.getSingleResult()).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.getSummary();

        assertEquals(10, summary.drift()); // 60 - 50 = +10
        assertFalse(summary.invariantOk(), "50 + 20 + 10 == 80 != 100 => invariant must be false");
    }
}

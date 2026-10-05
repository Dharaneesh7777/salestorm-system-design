package com.salestorm.infrastructure;

import com.salestorm.inventory.domain.ReservationStatus;
import com.salestorm.inventory.infrastructure.entity.InventoryEntity;
import com.salestorm.inventory.infrastructure.entity.InventoryReservationEntity;
import com.salestorm.payment.infrastructure.entity.RefundEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class DatabaseSchemaAndConstraintsTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        jdbcTemplate.update("UPDATE INVENTORY SET available_quantity = 100, reserved_quantity = 0, sold_quantity = 0 WHERE product_id = 1");
    }

    @Test
    @DisplayName("Verify Product X was seeded with 100 total and available units on startup")
    void testProductXSeeded() {
        Integer available = jdbcTemplate.queryForObject(
                "SELECT available_quantity FROM INVENTORY WHERE product_id = 1",
                Integer.class
        );
        Integer total = jdbcTemplate.queryForObject(
                "SELECT total_stock FROM INVENTORY WHERE product_id = 1",
                Integer.class
        );

        assertEquals(100, total);
        assertEquals(100, available);
    }

    @Test
    @DisplayName("Verify check constraint: available + reserved + sold = total_stock")
    void testInventoryStockEquationConstraint() {
        // Attempting to insert mismatched quantities should fail with DataIntegrityViolationException
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO INVENTORY (product_id, total_stock, available_quantity, reserved_quantity, sold_quantity, version, updated_at) " +
                            "VALUES (999, 100, 50, 20, 10, 0, CURRENT_TIMESTAMP)"
            );
        });
    }

    @Test
    @DisplayName("Verify exactly one active reservation per customer per product constraint")
    void testSingleActiveReservationPerCustomerProduct() {
        Long customerId = 501L;
        Long productId = 1001L;

        // 1. Insert first active reservation
        jdbcTemplate.update(
                "INSERT INTO INVENTORY_RESERVATION (reservation_id, product_id, customer_id, order_id, quantity, status, expires_at, idempotency_key, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), productId, customerId, "ORD-1", 1, "RESERVED",
                Instant.now().plus(5, ChronoUnit.MINUTES), "IDEMP-1"
        );

        // 2. Second active reservation for same customer and product must fail due to unique generated column
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO INVENTORY_RESERVATION (reservation_id, product_id, customer_id, order_id, quantity, status, expires_at, idempotency_key, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), productId, customerId, "ORD-2", 1, "RESERVED",
                    Instant.now().plus(5, ChronoUnit.MINUTES), "IDEMP-2"
            );
        });

        // 3. Inactive reservations (e.g. RELEASED) for the same customer/product are permitted
        assertDoesNotThrow(() -> {
            jdbcTemplate.update(
                    "INSERT INTO INVENTORY_RESERVATION (reservation_id, product_id, customer_id, order_id, quantity, status, expires_at, idempotency_key, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), productId, customerId, "ORD-3", 1, "RELEASED",
                    Instant.now().minus(5, ChronoUnit.MINUTES), "IDEMP-3"
            );
        });
    }

    @Test
    @DisplayName("Verify unique idempotency_key constraint on reservation")
    void testReservationIdempotencyKeyUnique() {
        String idempKey = "SAME_KEY_123";

        jdbcTemplate.update(
                "INSERT INTO INVENTORY_RESERVATION (reservation_id, product_id, customer_id, order_id, quantity, status, expires_at, idempotency_key, created_at, updated_at) " +
                        "VALUES (?, 2, 1, 'ORD-A', 1, 'RESERVED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), Instant.now().plus(5, ChronoUnit.MINUTES), idempKey
        );

        assertThrows(Exception.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO INVENTORY_RESERVATION (reservation_id, product_id, customer_id, order_id, quantity, status, expires_at, idempotency_key, created_at, updated_at) " +
                            "VALUES (?, 3, 2, 'ORD-B', 1, 'RESERVED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    UUID.randomUUID().toString(), Instant.now().plus(5, ChronoUnit.MINUTES), idempKey
            );
        });
    }

    @Test
    @DisplayName("Verify unique payment_id on refund")
    void testUniquePaymentIdOnRefund() {
        String paymentId = "PAY-999";

        jdbcTemplate.update(
                "INSERT INTO PAYMENT (payment_id, order_id, amount, currency, status, idempotency_key, created_at, updated_at) " +
                        "VALUES (?, 'ORD-999', 99.99, 'USD', 'SUCCESS', 'IDEMP-PAY-999', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                paymentId
        );

        jdbcTemplate.update(
                "INSERT INTO REFUND (refund_id, payment_id, amount, status, reason, created_at) " +
                        "VALUES ('REF-1', ?, 99.99, 'COMPLETED', 'Customer returned', CURRENT_TIMESTAMP)",
                paymentId
        );

        // Second refund for same payment_id must fail
        assertThrows(Exception.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO REFUND (refund_id, payment_id, amount, status, reason, created_at) " +
                            "VALUES ('REF-2', ?, 99.99, 'COMPLETED', 'Duplicate refund', CURRENT_TIMESTAMP)",
                    paymentId
            );
        });
    }
}

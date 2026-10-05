-- Seed Product X (product_id = 1) with 100 total units and 100 available units (MySQL & H2 MySQL Mode compatible)
INSERT INTO INVENTORY (product_id, total_stock, available_quantity, reserved_quantity, sold_quantity, version, updated_at)
VALUES (1, 100, 100, 0, 0, 0, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE total_stock = total_stock;

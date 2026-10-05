-- =========================================================================
-- SaleStorm Database DDL Schema (H2 MySQL-Compatible Mode & MySQL 8.0)
-- =========================================================================

-- 1. INVENTORY TABLE
CREATE TABLE IF NOT EXISTS INVENTORY (
    inventory_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL UNIQUE,
    total_stock INT NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL,
    sold_quantity INT NOT NULL,
    version BIGINT DEFAULT 0,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT chk_inventory_stock_validity CHECK (
        available_quantity >= 0 AND
        reserved_quantity >= 0 AND
        sold_quantity >= 0 AND
        (available_quantity + reserved_quantity + sold_quantity = total_stock)
    )
);

-- 2. INVENTORY RESERVATION TABLE
CREATE TABLE IF NOT EXISTS INVENTORY_RESERVATION (
    reservation_id VARCHAR(64) PRIMARY KEY,
    product_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    order_id VARCHAR(64) UNIQUE,
    quantity INT NOT NULL CHECK (quantity > 0),
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    release_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    active_customer_product VARCHAR(128) GENERATED ALWAYS AS (
        CASE WHEN status IN ('RESERVED', 'PAYMENT_PENDING', 'CONFIRMED') THEN CONCAT(customer_id, ':', product_id) ELSE NULL END
    ),
    CONSTRAINT uk_res_active_cust_prod UNIQUE (active_customer_product)
);

CREATE INDEX IF NOT EXISTS idx_res_status_expires ON INVENTORY_RESERVATION (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_res_cust_prod ON INVENTORY_RESERVATION (customer_id, product_id);

-- 3. PAYMENT TABLE
CREATE TABLE IF NOT EXISTS PAYMENT (
    payment_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    reservation_id VARCHAR(64),
    amount DECIMAL(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_hash VARCHAR(64),
    gateway_ref VARCHAR(128),
    attempt_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pay_status_updated ON PAYMENT (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_pay_order_res ON PAYMENT (order_id, reservation_id);

-- 4. REFUND TABLE
CREATE TABLE IF NOT EXISTS REFUND (
    refund_id VARCHAR(64) PRIMARY KEY,
    payment_id VARCHAR(64) NOT NULL UNIQUE,
    amount DECIMAL(12, 2) NOT NULL CHECK (amount > 0),
    status VARCHAR(32) NOT NULL,
    reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES PAYMENT(payment_id)
);

-- 5. ORDERS TABLE
CREATE TABLE IF NOT EXISTS ORDERS (
    order_id VARCHAR(64) PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    reservation_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL CHECK (total_amount >= 0),
    currency VARCHAR(3) NOT NULL,
    cancel_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ord_cust_created ON ORDERS (customer_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ord_status_updated ON ORDERS (status, updated_at);

-- 6. ORDER ITEM TABLE
CREATE TABLE IF NOT EXISTS ORDER_ITEM (
    order_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(12, 2) NOT NULL,
    line_total DECIMAL(12, 2) NOT NULL,
    CONSTRAINT fk_order_item_orders FOREIGN KEY (order_id) REFERENCES ORDERS(order_id) ON DELETE CASCADE
);

-- 7. ORDER STATUS HISTORY TABLE
CREATE TABLE IF NOT EXISTS ORDER_STATUS_HISTORY (
    history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    old_status VARCHAR(32),
    new_status VARCHAR(32) NOT NULL,
    reason VARCHAR(255),
    event_id VARCHAR(64),
    changed_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_order_status_history_event UNIQUE (order_id, event_id),
    CONSTRAINT fk_order_history_orders FOREIGN KEY (order_id) REFERENCES ORDERS(order_id) ON DELETE CASCADE
);

-- 8. OUTBOX TABLE
CREATE TABLE IF NOT EXISTS OUTBOX (
    event_id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64),
    type VARCHAR(64) NOT NULL,
    version INT DEFAULT 1,
    payload LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_published_created ON OUTBOX (published_at, created_at);

-- 9. PROCESSED EVENT TABLE
CREATE TABLE IF NOT EXISTS PROCESSED_EVENT (
    event_id VARCHAR(64) NOT NULL,
    consumer VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id, consumer)
);

-- 10. SAGA STATE TABLE
CREATE TABLE IF NOT EXISTS SAGA_STATE (
    saga_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL UNIQUE,
    reservation_id VARCHAR(64),
    payment_id VARCHAR(64),
    customer_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    amount DECIMAL(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    status VARCHAR(32) NOT NULL,
    step VARCHAR(64),
    failure_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_saga_status_updated ON SAGA_STATE (status, updated_at);
CREATE INDEX IF NOT EXISTS idx_saga_cust_prod ON SAGA_STATE (customer_id, product_id);


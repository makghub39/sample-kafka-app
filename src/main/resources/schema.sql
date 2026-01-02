-- ═══════════════════════════════════════════════════════════════
-- SCHEMA FOR ORDER PROCESSING DEMO
-- ═══════════════════════════════════════════════════════════════

-- Customers table
CREATE TABLE IF NOT EXISTS customers (
    customer_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL,
    tier VARCHAR(20) NOT NULL  -- STANDARD, PREMIUM, GOLD
);

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    order_id VARCHAR(50) PRIMARY KEY,
    customer_id VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
);

-- Order items table
CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id VARCHAR(50) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    quantity INT NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
);

-- Inventory table
CREATE TABLE IF NOT EXISTS inventory (
    sku VARCHAR(50) PRIMARY KEY,
    product_name VARCHAR(100) NOT NULL,
    quantity_available INT NOT NULL,
    warehouse_location VARCHAR(50) NOT NULL
);

-- Order pricing table
CREATE TABLE IF NOT EXISTS order_pricing (
    order_id VARCHAR(50) PRIMARY KEY,
    base_price DECIMAL(10, 2) NOT NULL,
    discount DECIMAL(5, 4) NOT NULL,  -- e.g., 0.10 for 10%
    tax_rate DECIMAL(5, 4) NOT NULL,  -- e.g., 0.08 for 8%
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_sku ON order_items(sku);

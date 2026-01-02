-- ═══════════════════════════════════════════════════════════════
-- SCHEMA FOR ORDER PROCESSING DEMO - SQL SERVER / AZURE SQL EDGE
-- ═══════════════════════════════════════════════════════════════

-- Customers table
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'customers')
BEGIN
    CREATE TABLE customers (
        customer_id VARCHAR(50) PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        email VARCHAR(100) NOT NULL,
        tier VARCHAR(20) NOT NULL  -- STANDARD, PREMIUM, GOLD
    );
END;
GO

-- Orders table
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'orders')
BEGIN
    CREATE TABLE orders (
        order_id VARCHAR(50) PRIMARY KEY,
        customer_id VARCHAR(50) NOT NULL,
        status VARCHAR(20) NOT NULL,
        amount DECIMAL(10, 2) NOT NULL,
        created_at DATETIME2 NOT NULL,
        FOREIGN KEY (customer_id) REFERENCES customers(customer_id)
    );
END;
GO

-- Order items table
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'order_items')
BEGIN
    CREATE TABLE order_items (
        id BIGINT IDENTITY(1,1) PRIMARY KEY,
        order_id VARCHAR(50) NOT NULL,
        sku VARCHAR(50) NOT NULL,
        quantity INT NOT NULL,
        FOREIGN KEY (order_id) REFERENCES orders(order_id)
    );
END;
GO

-- Inventory table
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'inventory')
BEGIN
    CREATE TABLE inventory (
        sku VARCHAR(50) PRIMARY KEY,
        product_name VARCHAR(100) NOT NULL,
        quantity_available INT NOT NULL,
        warehouse_location VARCHAR(50) NOT NULL
    );
END;
GO

-- Order pricing table
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'order_pricing')
BEGIN
    CREATE TABLE order_pricing (
        order_id VARCHAR(50) PRIMARY KEY,
        base_price DECIMAL(10, 2) NOT NULL,
        discount DECIMAL(5, 4) NOT NULL,  -- e.g., 0.10 for 10%
        tax_rate DECIMAL(5, 4) NOT NULL,  -- e.g., 0.08 for 8%
        FOREIGN KEY (order_id) REFERENCES orders(order_id)
    );
END;
GO

-- Trading partners table
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'trading_partners')
BEGIN
    CREATE TABLE trading_partners (
        partner_id VARCHAR(50) PRIMARY KEY,
        partner_name VARCHAR(100) NOT NULL UNIQUE,
        status VARCHAR(20) NOT NULL,  -- ACTIVE, INACTIVE, SUSPENDED
        created_at DATETIME2 DEFAULT GETDATE(),
        updated_at DATETIME2 DEFAULT GETDATE()
    );
END;
GO

-- Business units table
IF NOT EXISTS (SELECT * FROM sys.tables WHERE name = 'business_units')
BEGIN
    CREATE TABLE business_units (
        unit_id VARCHAR(50) PRIMARY KEY,
        unit_name VARCHAR(100) NOT NULL UNIQUE,
        status VARCHAR(20) NOT NULL,  -- ACTIVE, INACTIVE, SUSPENDED
        created_at DATETIME2 DEFAULT GETDATE(),
        updated_at DATETIME2 DEFAULT GETDATE()
    );
END;
GO

-- Indexes for performance
IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_orders_customer')
    CREATE INDEX idx_orders_customer ON orders(customer_id);
GO

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_order_items_order')
    CREATE INDEX idx_order_items_order ON order_items(order_id);
GO

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_order_items_sku')
    CREATE INDEX idx_order_items_sku ON order_items(sku);
GO

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_trading_partners_name')
    CREATE INDEX idx_trading_partners_name ON trading_partners(partner_name);
GO

IF NOT EXISTS (SELECT * FROM sys.indexes WHERE name = 'idx_business_units_name')
    CREATE INDEX idx_business_units_name ON business_units(unit_name);
GO

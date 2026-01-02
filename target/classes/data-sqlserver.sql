-- ═══════════════════════════════════════════════════════════════
-- SAMPLE DATA FOR ORDER PROCESSING DEMO - SQL SERVER / AZURE SQL EDGE
-- ═══════════════════════════════════════════════════════════════

-- Clear existing data (if any) to allow re-running
DELETE FROM order_pricing;
DELETE FROM order_items;
DELETE FROM orders;
DELETE FROM inventory;
DELETE FROM customers;
DELETE FROM trading_partners;
DELETE FROM business_units;
GO

-- Insert Customers
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-001', 'John Smith', 'john.smith@email.com', 'GOLD');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-002', 'Jane Doe', 'jane.doe@email.com', 'PREMIUM');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-003', 'Bob Johnson', 'bob.johnson@email.com', 'STANDARD');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-004', 'Alice Williams', 'alice.williams@email.com', 'PREMIUM');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-005', 'Charlie Brown', 'charlie.brown@email.com', 'STANDARD');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-006', 'Diana Ross', 'diana.ross@email.com', 'GOLD');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-007', 'Edward Norton', 'edward.norton@email.com', 'STANDARD');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-008', 'Fiona Apple', 'fiona.apple@email.com', 'PREMIUM');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-009', 'George Lucas', 'george.lucas@email.com', 'GOLD');
INSERT INTO customers (customer_id, name, email, tier) VALUES ('CUST-010', 'Helen Troy', 'helen.troy@email.com', 'STANDARD');
GO

-- Insert Inventory
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-001', 'Laptop Pro', 50, 'WAREHOUSE-EAST');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-002', 'Wireless Mouse', 200, 'WAREHOUSE-WEST');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-003', 'USB-C Cable', 500, 'WAREHOUSE-EAST');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-004', 'Monitor 27"', 30, 'WAREHOUSE-CENTRAL');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-005', 'Keyboard Mechanical', 100, 'WAREHOUSE-WEST');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-006', 'Webcam HD', 75, 'WAREHOUSE-EAST');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-007', 'Headphones Wireless', 150, 'WAREHOUSE-CENTRAL');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-008', 'Tablet Stand', 80, 'WAREHOUSE-WEST');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-009', 'Power Bank', 0, 'WAREHOUSE-EAST');
INSERT INTO inventory (sku, product_name, quantity_available, warehouse_location) VALUES ('SKU-010', 'HDMI Cable', 5, 'WAREHOUSE-CENTRAL');
GO

-- Insert Orders (100 orders for testing)
DECLARE @i INT = 1;
DECLARE @customer_id VARCHAR(50);
DECLARE @amount DECIMAL(10,2);

WHILE @i <= 100
BEGIN
    SET @customer_id = 'CUST-' + RIGHT('000' + CAST(((@i - 1) % 10) + 1 AS VARCHAR), 3);
    SET @amount = 50 + (@i * 10) + ((@i % 7) * 13.99);
    
    INSERT INTO orders (order_id, customer_id, status, amount, created_at)
    VALUES (
        'ORD-' + RIGHT('000' + CAST(@i AS VARCHAR), 3),
        @customer_id,
        'PENDING',
        @amount,
        GETDATE()
    );
    
    SET @i = @i + 1;
END;
GO

-- Insert Order Items (link orders to inventory SKUs)
DECLARE @j INT = 1;
DECLARE @sku VARCHAR(50);

WHILE @j <= 100
BEGIN
    SET @sku = 'SKU-' + RIGHT('000' + CAST(((@j - 1) % 10) + 1 AS VARCHAR), 3);
    
    INSERT INTO order_items (order_id, sku, quantity)
    VALUES (
        'ORD-' + RIGHT('000' + CAST(@j AS VARCHAR), 3),
        @sku,
        1 + (@j % 3)
    );
    
    SET @j = @j + 1;
END;
GO

-- Insert Order Pricing
DECLARE @k INT = 1;
DECLARE @base_price DECIMAL(10,2);
DECLARE @discount DECIMAL(5,4);

WHILE @k <= 100
BEGIN
    SET @base_price = 50 + (@k * 10) + ((@k % 7) * 13.99);
    SET @discount = CASE 
        WHEN @k % 4 = 0 THEN 0.15
        WHEN @k % 3 = 0 THEN 0.10
        WHEN @k % 2 = 0 THEN 0.05
        ELSE 0.00
    END;
    
    INSERT INTO order_pricing (order_id, base_price, discount, tax_rate)
    VALUES (
        'ORD-' + RIGHT('000' + CAST(@k AS VARCHAR), 3),
        @base_price,
        @discount,
        0.08
    );
    
    SET @k = @k + 1;
END;
GO

-- Verify data
SELECT 'Customers' AS TableName, COUNT(*) AS RecordCount FROM customers
UNION ALL
SELECT 'Orders', COUNT(*) FROM orders
UNION ALL
SELECT 'Order Items', COUNT(*) FROM order_items
UNION ALL
SELECT 'Inventory', COUNT(*) FROM inventory
UNION ALL
SELECT 'Order Pricing', COUNT(*) FROM order_pricing
UNION ALL
SELECT 'Trading Partners', COUNT(*) FROM trading_partners
UNION ALL
SELECT 'Business Units', COUNT(*) FROM business_units;
GO

-- ═══════════════════════════════════════════════════════════════
-- TRADING PARTNERS - For event validation
-- Events from inactive partners will be skipped
-- ═══════════════════════════════════════════════════════════════
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-001', 'ACME Corp', 'ACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-002', 'Global Traders', 'ACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-003', 'FastShip Inc', 'ACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-004', 'Budget Supplies', 'INACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-005', 'Premium Partners', 'ACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-006', 'Discount Depot', 'SUSPENDED');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-007', 'Enterprise Solutions', 'ACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-008', 'SmallBiz LLC', 'INACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-009', 'MegaCorp', 'ACTIVE');
INSERT INTO trading_partners (partner_id, partner_name, status) VALUES ('TP-010', 'TestPartner', 'ACTIVE');
GO

-- ═══════════════════════════════════════════════════════════════
-- BUSINESS UNITS - For event validation
-- Events from inactive units will be skipped (if partner also inactive)
-- ═══════════════════════════════════════════════════════════════
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-001', 'Retail Division', 'ACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-002', 'Wholesale Division', 'ACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-003', 'E-Commerce', 'ACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-004', 'Legacy Systems', 'INACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-005', 'International', 'ACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-006', 'Domestic Only', 'SUSPENDED');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-007', 'B2B Operations', 'ACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-008', 'Discontinued Unit', 'INACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-009', 'New Ventures', 'ACTIVE');
INSERT INTO business_units (unit_id, unit_name, status) VALUES ('BU-010', 'TestUnit', 'ACTIVE');
GO

#!/bin/bash
# Initialize SQL Server database with sample data

CONTAINER_NAME="sqlserver"
SA_PASSWORD="YourStrong@Passw0rd"
DATABASE="ordersdb"
NETWORK="kafka-order-processor_order-network"

echo "=== SQL Server Initialization ==="

# Create database
echo "Creating database..."
docker run --rm --network $NETWORK mcr.microsoft.com/mssql-tools:latest \
  /opt/mssql-tools/bin/sqlcmd -S sqlserver -U sa -P "$SA_PASSWORD" \
  -Q "IF NOT EXISTS (SELECT * FROM sys.databases WHERE name = '$DATABASE') CREATE DATABASE $DATABASE"

# Insert customers
echo "Inserting customers..."
docker run --rm --network $NETWORK mcr.microsoft.com/mssql-tools:latest \
  /opt/mssql-tools/bin/sqlcmd -S sqlserver -U sa -P "$SA_PASSWORD" -d $DATABASE \
  -Q "IF NOT EXISTS (SELECT 1 FROM customers) BEGIN
      INSERT INTO customers VALUES ('CUST-001', 'John Smith', 'john@email.com', 'GOLD');
      INSERT INTO customers VALUES ('CUST-002', 'Jane Doe', 'jane@email.com', 'PREMIUM');
      INSERT INTO customers VALUES ('CUST-003', 'Bob Johnson', 'bob@email.com', 'STANDARD');
      INSERT INTO customers VALUES ('CUST-004', 'Alice Williams', 'alice@email.com', 'PREMIUM');
      INSERT INTO customers VALUES ('CUST-005', 'Charlie Brown', 'charlie@email.com', 'STANDARD');
      INSERT INTO customers VALUES ('CUST-006', 'Diana Ross', 'diana@email.com', 'GOLD');
      INSERT INTO customers VALUES ('CUST-007', 'Edward Norton', 'edward@email.com', 'STANDARD');
      INSERT INTO customers VALUES ('CUST-008', 'Fiona Apple', 'fiona@email.com', 'PREMIUM');
      INSERT INTO customers VALUES ('CUST-009', 'George Lucas', 'george@email.com', 'GOLD');
      INSERT INTO customers VALUES ('CUST-010', 'Helen Troy', 'helen@email.com', 'STANDARD');
      END"

# Insert inventory
echo "Inserting inventory..."
docker run --rm --network $NETWORK mcr.microsoft.com/mssql-tools:latest \
  /opt/mssql-tools/bin/sqlcmd -S sqlserver -U sa -P "$SA_PASSWORD" -d $DATABASE \
  -Q "IF NOT EXISTS (SELECT 1 FROM inventory) BEGIN
      INSERT INTO inventory VALUES ('SKU-001', 'Laptop Pro', 50, 'WAREHOUSE-EAST');
      INSERT INTO inventory VALUES ('SKU-002', 'Wireless Mouse', 200, 'WAREHOUSE-WEST');
      INSERT INTO inventory VALUES ('SKU-003', 'USB-C Cable', 500, 'WAREHOUSE-EAST');
      INSERT INTO inventory VALUES ('SKU-004', 'Monitor 27in', 30, 'WAREHOUSE-CENTRAL');
      INSERT INTO inventory VALUES ('SKU-005', 'Keyboard', 100, 'WAREHOUSE-WEST');
      INSERT INTO inventory VALUES ('SKU-006', 'Webcam HD', 75, 'WAREHOUSE-EAST');
      INSERT INTO inventory VALUES ('SKU-007', 'Headphones', 150, 'WAREHOUSE-CENTRAL');
      INSERT INTO inventory VALUES ('SKU-008', 'Tablet Stand', 80, 'WAREHOUSE-WEST');
      INSERT INTO inventory VALUES ('SKU-009', 'Power Bank', 0, 'WAREHOUSE-EAST');
      INSERT INTO inventory VALUES ('SKU-010', 'HDMI Cable', 5, 'WAREHOUSE-CENTRAL');
      END"

# Insert orders (100 orders)
echo "Inserting orders..."
docker run --rm --network $NETWORK mcr.microsoft.com/mssql-tools:latest \
  /opt/mssql-tools/bin/sqlcmd -S sqlserver -U sa -P "$SA_PASSWORD" -d $DATABASE \
  -Q "IF NOT EXISTS (SELECT 1 FROM orders) BEGIN
      DECLARE @i INT = 1;
      WHILE @i <= 100 BEGIN
        INSERT INTO orders (order_id, customer_id, status, amount, created_at)
        VALUES (
          CONCAT('ORD-', RIGHT('000' + CAST(@i AS VARCHAR), 3)),
          CONCAT('CUST-', RIGHT('000' + CAST(((@i - 1) % 10) + 1 AS VARCHAR), 3)),
          'PENDING',
          50 + (@i * 10),
          GETDATE()
        );
        SET @i = @i + 1;
      END
      END"

# Insert order_items
echo "Inserting order items..."
docker run --rm --network $NETWORK mcr.microsoft.com/mssql-tools:latest \
  /opt/mssql-tools/bin/sqlcmd -S sqlserver -U sa -P "$SA_PASSWORD" -d $DATABASE \
  -Q "IF NOT EXISTS (SELECT 1 FROM order_items) BEGIN
      DECLARE @j INT = 1;
      WHILE @j <= 100 BEGIN
        INSERT INTO order_items (order_id, sku, quantity)
        VALUES (
          CONCAT('ORD-', RIGHT('000' + CAST(@j AS VARCHAR), 3)),
          CONCAT('SKU-', RIGHT('000' + CAST(((@j - 1) % 10) + 1 AS VARCHAR), 3)),
          1 + (@j % 3)
        );
        SET @j = @j + 1;
      END
      END"

# Insert order_pricing
echo "Inserting order pricing..."
docker run --rm --network $NETWORK mcr.microsoft.com/mssql-tools:latest \
  /opt/mssql-tools/bin/sqlcmd -S sqlserver -U sa -P "$SA_PASSWORD" -d $DATABASE \
  -Q "IF NOT EXISTS (SELECT 1 FROM order_pricing) BEGIN
      DECLARE @k INT = 1;
      WHILE @k <= 100 BEGIN
        INSERT INTO order_pricing (order_id, base_price, discount, tax_rate)
        VALUES (
          CONCAT('ORD-', RIGHT('000' + CAST(@k AS VARCHAR), 3)),
          50 + (@k * 10),
          CASE WHEN @k % 4 = 0 THEN 0.15 WHEN @k % 3 = 0 THEN 0.10 WHEN @k % 2 = 0 THEN 0.05 ELSE 0.00 END,
          0.08
        );
        SET @k = @k + 1;
      END
      END"

# Verify data
echo ""
echo "=== Data Summary ==="
docker run --rm --network $NETWORK mcr.microsoft.com/mssql-tools:latest \
  /opt/mssql-tools/bin/sqlcmd -S sqlserver -U sa -P "$SA_PASSWORD" -d $DATABASE \
  -Q "SELECT 'Customers' AS [Table], COUNT(*) AS [Count] FROM customers
      UNION ALL SELECT 'Orders', COUNT(*) FROM orders
      UNION ALL SELECT 'OrderItems', COUNT(*) FROM order_items
      UNION ALL SELECT 'Inventory', COUNT(*) FROM inventory
      UNION ALL SELECT 'OrderPricing', COUNT(*) FROM order_pricing"

echo ""
echo "=== SQL Server Initialization Complete ==="

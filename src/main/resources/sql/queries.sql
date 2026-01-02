-- name: findOrdersByIds
SELECT order_id, customer_id, status, amount, created_at
FROM orders
WHERE order_id IN (:orderIds)

-- name: batchFetchCustomerData
SELECT o.order_id, c.customer_id, c.name, c.email, c.tier
FROM customers c
INNER JOIN orders o ON c.customer_id = o.customer_id
WHERE o.order_id IN (:orderIds)

-- name: batchFetchInventoryData
SELECT oi.order_id, i.sku, i.quantity_available, i.warehouse_location
FROM inventory i
INNER JOIN order_items oi ON i.sku = oi.sku
WHERE oi.order_id IN (:orderIds)

-- name: batchFetchPricingData
SELECT order_id, base_price, discount, tax_rate
FROM order_pricing
WHERE order_id IN (:orderIds)

-- name: findTradingPartnerByName
SELECT partner_id, partner_name, status, updated_at
FROM trading_partners
WHERE partner_name = :partnerName

-- name: findBusinessUnitByName
SELECT unit_id, unit_name, status, updated_at
FROM business_units
WHERE unit_name = :unitName

// ═══════════════════════════════════════════════════════════════
// MongoDB Initialization Script
// Creates database, collections, indexes, and sample data
// ═══════════════════════════════════════════════════════════════

// Switch to ordersdb database
db = db.getSiblingDB('ordersdb');

// Create user for the application
db.createUser({
    user: 'orderapp',
    pwd: 'orderpass',
    roles: [
        { role: 'readWrite', db: 'ordersdb' }
    ]
});

print('Created application user: orderapp');

// ═══════════════════════════════════════════════════════════════
// Create Collections with Schema Validation
// ═══════════════════════════════════════════════════════════════

// Orders Collection
db.createCollection('orders', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['orderId', 'customerId', 'status', 'amount', 'createdAt'],
            properties: {
                orderId: { bsonType: 'string' },
                customerId: { bsonType: 'string' },
                status: { enum: ['PENDING', 'PROCESSING', 'COMPLETED', 'CANCELLED'] },
                amount: { bsonType: 'decimal' },
                createdAt: { bsonType: 'date' },
                items: {
                    bsonType: 'array',
                    items: {
                        bsonType: 'object',
                        properties: {
                            sku: { bsonType: 'string' },
                            quantity: { bsonType: 'int' },
                            price: { bsonType: 'decimal' }
                        }
                    }
                }
            }
        }
    }
});

// Customers Collection
db.createCollection('customers');

// Inventory Collection
db.createCollection('inventory');

// Order Pricing Collection
db.createCollection('orderPricing');

print('Created collections: orders, customers, inventory, orderPricing');

// ═══════════════════════════════════════════════════════════════
// Create Indexes for Performance
// ═══════════════════════════════════════════════════════════════

db.orders.createIndex({ orderId: 1 }, { unique: true });
db.orders.createIndex({ customerId: 1 });
db.orders.createIndex({ status: 1 });
db.orders.createIndex({ createdAt: -1 });

db.customers.createIndex({ customerId: 1 }, { unique: true });

db.inventory.createIndex({ sku: 1 }, { unique: true });

db.orderPricing.createIndex({ orderId: 1 }, { unique: true });

print('Created indexes');

// ═══════════════════════════════════════════════════════════════
// Insert Sample Customers
// ═══════════════════════════════════════════════════════════════

const customers = [
    { customerId: 'CUST-001', name: 'John Smith', email: 'john.smith@email.com', tier: 'GOLD' },
    { customerId: 'CUST-002', name: 'Jane Doe', email: 'jane.doe@email.com', tier: 'PREMIUM' },
    { customerId: 'CUST-003', name: 'Bob Johnson', email: 'bob.johnson@email.com', tier: 'STANDARD' },
    { customerId: 'CUST-004', name: 'Alice Williams', email: 'alice.williams@email.com', tier: 'PREMIUM' },
    { customerId: 'CUST-005', name: 'Charlie Brown', email: 'charlie.brown@email.com', tier: 'STANDARD' },
    { customerId: 'CUST-006', name: 'Diana Ross', email: 'diana.ross@email.com', tier: 'GOLD' },
    { customerId: 'CUST-007', name: 'Edward Norton', email: 'edward.norton@email.com', tier: 'STANDARD' },
    { customerId: 'CUST-008', name: 'Fiona Apple', email: 'fiona.apple@email.com', tier: 'PREMIUM' },
    { customerId: 'CUST-009', name: 'George Lucas', email: 'george.lucas@email.com', tier: 'GOLD' },
    { customerId: 'CUST-010', name: 'Helen Troy', email: 'helen.troy@email.com', tier: 'STANDARD' }
];

db.customers.insertMany(customers);
print('Inserted ' + customers.length + ' customers');

// ═══════════════════════════════════════════════════════════════
// Insert Sample Inventory
// ═══════════════════════════════════════════════════════════════

const inventory = [
    { sku: 'SKU-001', productName: 'Laptop Pro', quantityAvailable: 50, warehouseLocation: 'WAREHOUSE-EAST' },
    { sku: 'SKU-002', productName: 'Wireless Mouse', quantityAvailable: 200, warehouseLocation: 'WAREHOUSE-WEST' },
    { sku: 'SKU-003', productName: 'USB-C Cable', quantityAvailable: 500, warehouseLocation: 'WAREHOUSE-EAST' },
    { sku: 'SKU-004', productName: 'Monitor 27"', quantityAvailable: 30, warehouseLocation: 'WAREHOUSE-CENTRAL' },
    { sku: 'SKU-005', productName: 'Keyboard Mechanical', quantityAvailable: 100, warehouseLocation: 'WAREHOUSE-WEST' },
    { sku: 'SKU-006', productName: 'Webcam HD', quantityAvailable: 75, warehouseLocation: 'WAREHOUSE-EAST' },
    { sku: 'SKU-007', productName: 'Headphones Wireless', quantityAvailable: 150, warehouseLocation: 'WAREHOUSE-CENTRAL' },
    { sku: 'SKU-008', productName: 'Tablet Stand', quantityAvailable: 80, warehouseLocation: 'WAREHOUSE-WEST' },
    { sku: 'SKU-009', productName: 'Power Bank', quantityAvailable: 0, warehouseLocation: 'WAREHOUSE-EAST' },
    { sku: 'SKU-010', productName: 'HDMI Cable', quantityAvailable: 5, warehouseLocation: 'WAREHOUSE-CENTRAL' }
];

db.inventory.insertMany(inventory);
print('Inserted ' + inventory.length + ' inventory items');

// ═══════════════════════════════════════════════════════════════
// Insert Sample Orders (100 orders)
// ═══════════════════════════════════════════════════════════════

const skus = ['SKU-001', 'SKU-002', 'SKU-003', 'SKU-004', 'SKU-005', 'SKU-006', 'SKU-007', 'SKU-008', 'SKU-009', 'SKU-010'];
const orders = [];
const orderPricing = [];

for (let i = 1; i <= 100; i++) {
    const orderId = 'ORD-' + String(i).padStart(3, '0');
    const customerId = 'CUST-' + String(((i - 1) % 10) + 1).padStart(3, '0');
    const sku = skus[(i - 1) % 10];
    const basePrice = 100 + (i * 10);
    const discount = (i % 5) * 0.05;
    const amount = basePrice * (1 - discount);

    orders.push({
        orderId: orderId,
        customerId: customerId,
        status: 'PENDING',
        amount: NumberDecimal(amount.toFixed(2)),
        createdAt: new Date(),
        items: [
            { sku: sku, quantity: 1, price: NumberDecimal(basePrice.toFixed(2)) }
        ]
    });

    orderPricing.push({
        orderId: orderId,
        basePrice: NumberDecimal(basePrice.toFixed(2)),
        discount: NumberDecimal(discount.toFixed(4)),
        taxRate: NumberDecimal('0.0800')
    });
}

db.orders.insertMany(orders);
print('Inserted ' + orders.length + ' orders');

db.orderPricing.insertMany(orderPricing);
print('Inserted ' + orderPricing.length + ' order pricing records');

// ═══════════════════════════════════════════════════════════════
// Verify Data
// ═══════════════════════════════════════════════════════════════

print('');
print('═══════════════════════════════════════════════════════════════');
print('MongoDB Initialization Complete!');
print('═══════════════════════════════════════════════════════════════');
print('Database: ordersdb');
print('Collections:');
print('  - orders: ' + db.orders.countDocuments() + ' documents');
print('  - customers: ' + db.customers.countDocuments() + ' documents');
print('  - inventory: ' + db.inventory.countDocuments() + ' documents');
print('  - orderPricing: ' + db.orderPricing.countDocuments() + ' documents');
print('');
print('Connection string for app: mongodb://orderapp:orderpass@localhost:27017/ordersdb');
print('═══════════════════════════════════════════════════════════════');

# Kafka Order Processor - Demo Application

A Spring Boot application demonstrating optimized Kafka consumer processing with:
- **Virtual Threads (Java 21)** for lightweight concurrency
- **Batch DB calls** using `IN` clause (3 queries instead of N×3)
- **Parallel processing** with controlled concurrency via Semaphores
- **Manual Kafka commit** for data safety
- **H2 in-memory database** for easy testing

## Project Structure

```
kafka-order-processor/
├── src/main/java/com/example/
│   ├── KafkaOrderProcessorApplication.java   # Main entry point
│   ├── config/
│   │   ├── KafkaConfig.java                  # Kafka consumer/producer config
│   │   └── ExecutorConfig.java               # Virtual thread executors
│   ├── model/
│   │   ├── OrderEvent.java                   # Kafka event model
│   │   ├── Order.java                        # Order entity
│   │   ├── CustomerData.java                 # Customer DB data
│   │   ├── InventoryData.java                # Inventory DB data
│   │   ├── PricingData.java                  # Pricing DB data
│   │   ├── ProcessedOrder.java               # Output model
│   │   ├── FailedOrder.java                  # Failed order for DLQ
│   │   └── ProcessingResult.java             # Batch result wrapper
│   ├── repository/
│   │   └── OrderRepository.java              # Batch DB operations
│   ├── service/
│   │   ├── OrderProcessor.java               # Main processing logic
│   │   ├── WmqPublisher.java                 # Mock WMQ publisher
│   │   └── DeadLetterPublisher.java          # DLQ publisher
│   ├── listener/
│   │   └── OrderEventListener.java           # Kafka batch listener
│   └── controller/
│       └── TestController.java               # REST endpoints for testing
└── src/main/resources/
    ├── application.yml                       # Configuration
    ├── schema.sql                            # Database schema
    └── data.sql                              # Sample data (100 orders)
```

## Prerequisites

- **Java 21+** (for Virtual Threads)
- **Maven 3.8+**
- **Kafka** (optional - for full Kafka testing)

## Running the Application

### Option 1: Without Kafka (Direct Processing Test)

```bash
cd kafka-order-processor
./mvnw spring-boot:run
```

Then test the direct processing endpoint:

```bash
# Process 50 orders directly (bypasses Kafka)
curl -X POST "http://localhost:8080/api/process/direct?orderCount=50"

# Run benchmark comparison
curl "http://localhost:8080/api/benchmark?orderCount=50"
```

### Option 2: With Kafka

1. Start Kafka (using Docker):
```bash
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_CFG_NODE_ID=0 \
  -e KAFKA_CFG_PROCESS_ROLES=controller,broker \
  -e KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@localhost:9093 \
  -e KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  bitnami/kafka:latest
```

2. Create the topic:
```bash
docker exec -it kafka kafka-topics.sh --create \
  --topic order-events \
  --bootstrap-server localhost:9092 \
  --partitions 20
```

3. Run the application:
```bash
./mvnw spring-boot:run
```

4. Send test events:
```bash
# Send single event with 50 orders
curl -X POST "http://localhost:8080/api/kafka/send?orderCount=50"

# Send batch of 5 events with 20 orders each
curl -X POST "http://localhost:8080/api/kafka/send-batch?eventCount=5&ordersPerEvent=20"
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/health` | GET | Health check |
| `/api/process/direct?orderCount=N` | POST | Direct processing (no Kafka) |
| `/api/benchmark?orderCount=N` | GET | Compare sequential vs parallel |
| `/api/kafka/send?orderCount=N` | POST | Send test event to Kafka |
| `/api/kafka/send-batch?eventCount=N&ordersPerEvent=M` | POST | Send multiple events |
| `/api/sample` | GET | Get sample processed order |

## H2 Console

Access the database at: http://localhost:8080/h2-console

- **JDBC URL**: `jdbc:h2:mem:ordersdb`
- **Username**: `sa`
- **Password**: (empty)

## Key Optimizations Demonstrated

### 1. Batch DB Calls
```java
// Instead of N×3 individual queries:
// for (order : orders) { db.call1(order); db.call2(order); db.call3(order); }

// We use 3 batch queries with IN clause:
SELECT * FROM customers WHERE order_id IN (:orderIds)  -- 1 query for ALL
SELECT * FROM inventory WHERE order_id IN (:orderIds)  -- 1 query for ALL
SELECT * FROM pricing WHERE order_id IN (:orderIds)    -- 1 query for ALL
```

### 2. Parallel DB Calls
```java
// All 3 batch queries run simultaneously
CompletableFuture<Map<String, CustomerData>> f1 = supplyAsync(() -> fetchCustomers(ids));
CompletableFuture<Map<String, InventoryData>> f2 = supplyAsync(() -> fetchInventory(ids));
CompletableFuture<Map<String, PricingData>> f3 = supplyAsync(() -> fetchPricing(ids));
CompletableFuture.allOf(f1, f2, f3).join();  // Wait for all
```

### 3. Virtual Threads with Controlled Concurrency
```java
// Virtual threads are lightweight but we control concurrency
// to prevent resource exhaustion (DB connections, etc.)
@Bean("processingExecutor")
public ExecutorService processingExecutor() {
    Semaphore semaphore = new Semaphore(50);  // Max 50 concurrent
    return new VirtualThreadExecutorWithSemaphore(semaphore);
}
```

### 4. Manual Kafka Commit
```java
@KafkaListener(topics = "order-events")
public void consume(List<ConsumerRecord<...>> records, Acknowledgment ack) {
    try {
        processOrders(records);
        wmq.send(results);
        ack.acknowledge();  // Commit ONLY after success
    } catch (Exception e) {
        // Don't acknowledge - Kafka will redeliver
        throw e;
    }
}
```

## Performance Comparison

For 50 orders with 3 DB calls each (simulated 100ms per call):

| Approach | Time | Explanation |
|----------|------|-------------|
| Sequential | ~15,000ms | 50 × 3 × 100ms |
| Optimized | ~200-400ms | 3 parallel batch queries + parallel processing |
| **Speedup** | **~50x** | |

## Configuration

Key settings in `application.yml`:

```yaml
app:
  executor:
    processing-concurrency: 50    # Max parallel order processing
    db-concurrency: 10            # Max parallel DB operations

spring:
  kafka:
    consumer:
      enable-auto-commit: false   # Manual commit for safety
      max-poll-records: 500       # Batch size
```

## License

MIT

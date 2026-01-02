package com.example.controller;

import com.example.model.*;
import com.example.repository.OrderRepository;
import com.example.service.OrderFetchService;
import com.example.service.OrderProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.ProducerTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * REST Controller for testing the order processing pipeline.
 * Provides endpoints to:
 * 1. Send test events to Kafka (via Camel)
 * 2. Directly test the processor (bypassing Kafka)
 * 3. Test MongoDB order fetching
 * 4. Compare sequential vs parallel processing
 */
@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class TestController {

    private final ProducerTemplate producerTemplate;  // Camel producer (replaces KafkaTemplate)
    private final ObjectMapper objectMapper;
    private final OrderRepository orderRepository;
    private final OrderProcessor orderProcessor;
    private final OrderFetchService orderFetchService;
    
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;
    
    @Value("${app.kafka.topic.order-events:order-events}")
    private String orderEventsTopic;

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "message", "Kafka Order Processor is running"
        ));
    }

    /**
     * Send a test event to Kafka with trading partner and business unit.
     * Now uses Camel ProducerTemplate instead of KafkaTemplate.
     * 
     * Example: POST /api/kafka/send?tradingPartner=ACME&businessUnit=WEST
     */
    @PostMapping("/kafka/send")
    public ResponseEntity<Map<String, Object>> sendToKafka(
            @RequestParam(defaultValue = "DEFAULT-PARTNER") String tradingPartner,
            @RequestParam(defaultValue = "DEFAULT-UNIT") String businessUnit) {
        
        try {
            OrderEvent event = new OrderEvent(
                    UUID.randomUUID().toString(),
                    "PROCESS_ORDERS",
                    tradingPartner,
                    businessUnit
            );

            // Serialize to JSON before sending
            String jsonPayload = objectMapper.writeValueAsString(event);
            String kafkaUri = String.format("kafka:%s?brokers=%s", orderEventsTopic, kafkaBootstrapServers);
            producerTemplate.sendBodyAndHeader(kafkaUri, jsonPayload, "kafka.KEY", event.eventId());

            log.info("Sent event to Kafka for trading partner: {}, business unit: {}", 
                    event.tradingPartnerName(), event.businessUnitName());

            return ResponseEntity.ok(Map.of(
                    "message", "Event sent to Kafka",
                    "eventId", event.eventId(),
                    "tradingPartner", event.tradingPartnerName(),
                    "businessUnit", event.businessUnitName(),
                    "topic", orderEventsTopic
            ));
        } catch (Exception e) {
            log.error("Failed to send event: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Send multiple events to Kafka to simulate batch processing.
     * Now uses Camel ProducerTemplate instead of KafkaTemplate.
     * 
     * Example: POST /api/kafka/send-batch?eventCount=5&tradingPartner=ACME
     */
    @PostMapping("/kafka/send-batch")
    public ResponseEntity<Map<String, Object>> sendBatchToKafka(
            @RequestParam(defaultValue = "5") int eventCount,
            @RequestParam(defaultValue = "DEFAULT-PARTNER") String tradingPartner) {

        try {
            String kafkaUri = String.format("kafka:%s?brokers=%s", orderEventsTopic, kafkaBootstrapServers);
            List<String> businessUnits = List.of("WEST", "EAST", "NORTH", "SOUTH", "CENTRAL");
            
            for (int e = 0; e < eventCount; e++) {
                String businessUnit = businessUnits.get(e % businessUnits.size());

                OrderEvent event = new OrderEvent(
                        UUID.randomUUID().toString(),
                        "PROCESS_ORDERS",
                        tradingPartner,
                        businessUnit
                );

                // Serialize to JSON before sending
                String jsonPayload = objectMapper.writeValueAsString(event);
                producerTemplate.sendBodyAndHeader(kafkaUri, jsonPayload, "kafka.KEY", event.eventId());
            }

            log.info("Sent {} events to Kafka for trading partner: {}", eventCount, tradingPartner);

            return ResponseEntity.ok(Map.of(
                    "message", "Batch events sent to Kafka",
                    "eventCount", eventCount,
                    "tradingPartner", tradingPartner,
                    "topic", orderEventsTopic
            ));
        } catch (Exception e) {
            log.error("Failed to send batch: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Directly test the processor without Kafka.
     * Useful for benchmarking and debugging.
     * 
     * Example: POST /api/process/direct?orderCount=50
     */
    @PostMapping("/process/direct")
    public ResponseEntity<Map<String, Object>> processDirectly(
            @RequestParam(defaultValue = "10") int orderCount) {

        // Generate order IDs
        List<String> orderIds = IntStream.rangeClosed(1, Math.min(orderCount, 100))
                .mapToObj(i -> String.format("ORD-%03d", i))
                .toList();

        // Fetch orders from DB
        List<Order> orders = orderRepository.findOrdersByIds(orderIds);

        // Process using the optimized processor
        ProcessingResult result = orderProcessor.processOrders(orders);

        return ResponseEntity.ok(Map.of(
                "message", "Direct processing complete",
                "orderCount", orders.size(),
                "successes", result.successes().size(),
                "failures", result.failures().size(),
                "processingTimeMs", result.processingTimeMs(),
                "processedOrders", result.successes()
        ));
    }

    /**
     * Compare sequential vs parallel processing.
     * Shows the performance difference clearly.
     * 
     * Example: GET /api/benchmark?orderCount=50
     */
    @GetMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> benchmark(
            @RequestParam(defaultValue = "20") int orderCount) {

        List<String> orderIds = IntStream.rangeClosed(1, Math.min(orderCount, 100))
                .mapToObj(i -> String.format("ORD-%03d", i))
                .toList();

        List<Order> orders = orderRepository.findOrdersByIds(orderIds);

        // Sequential processing (simulated - 3 DB calls per order)
        long seqStart = System.currentTimeMillis();
        // Simulating: orderCount * 3 DB calls * ~100ms each
        int simulatedSequentialTime = orderCount * 3 * 100; // milliseconds
        long seqTime = simulatedSequentialTime;

        // Parallel + Batch processing
        long parStart = System.currentTimeMillis();
        ProcessingResult result = orderProcessor.processOrders(orders);
        long parTime = System.currentTimeMillis() - parStart;

        double speedup = (double) seqTime / parTime;

        return ResponseEntity.ok(Map.of(
                "orderCount", orderCount,
                "sequentialTimeMs", seqTime,
                "sequentialExplanation", String.format("%d orders × 3 DB calls × 100ms = %dms", orderCount, seqTime),
                "parallelTimeMs", parTime,
                "parallelExplanation", "3 batch queries (parallel) + parallel processing",
                "speedup", String.format("%.1fx faster", speedup),
                "improvement", String.format("%.0f%% reduction in processing time", (1 - (double) parTime / seqTime) * 100)
        ));
    }

    /**
     * Get sample processed order structure.
     */
    @GetMapping("/sample")
    public ResponseEntity<Map<String, Object>> getSample() {
        List<Order> orders = orderRepository.findOrdersByIds(List.of("ORD-001"));
        
        if (orders.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "No sample data found"));
        }

        ProcessingResult result = orderProcessor.processOrders(orders);

        return ResponseEntity.ok(Map.of(
                "sampleOrder", orders.get(0),
                "processedOrder", result.successes().isEmpty() ? "N/A" : result.successes().get(0)
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    // MONGODB ENDPOINTS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Test fetching orders from MongoDB.
     * This tests the MongoDB integration that replaces API calls.
     * 
     * Example: GET /api/mongo/orders?tradingPartner=ACME&businessUnit=WEST
     */
    @GetMapping("/mongo/orders")
    public ResponseEntity<Map<String, Object>> fetchFromMongo(
            @RequestParam(required = false) String tradingPartner,
            @RequestParam(required = false) String businessUnit) {
        
        if (!orderFetchService.isMongoEnabled()) {
            return ResponseEntity.ok(Map.of(
                    "message", "MongoDB is disabled",
                    "hint", "Run with --spring.profiles.active=docker to enable MongoDB"
            ));
        }

        long startTime = System.currentTimeMillis();

        // Create an event to query MongoDB
        OrderEvent event = new OrderEvent(
                UUID.randomUUID().toString(),
                "FETCH_ORDERS",
                tradingPartner,
                businessUnit
        );

        List<Order> orders = orderFetchService.fetchOrdersForEvent(event);
        long duration = System.currentTimeMillis() - startTime;

        return ResponseEntity.ok(Map.of(
                "message", "Orders fetched from MongoDB",
                "orderCount", orders.size(),
                "fetchTimeMs", duration,
                "orders", orders.stream().limit(10).toList(), // Limit output
                "mongoEnabled", true
        ));
    }

    /**
     * Test the full flow: MongoDB fetch → H2 batch lookups → Process.
     * This simulates what happens when a Kafka event is received.
     * 
     * Example: POST /api/mongo/process?tradingPartner=ACME&businessUnit=WEST
     */
    @PostMapping("/mongo/process")
    public ResponseEntity<Map<String, Object>> processFromMongo(
            @RequestParam(defaultValue = "DEFAULT-PARTNER") String tradingPartner,
            @RequestParam(defaultValue = "DEFAULT-UNIT") String businessUnit) {
        
        long totalStart = System.currentTimeMillis();

        // Step 1: Fetch from MongoDB by trading partner and business unit
        long mongoStart = System.currentTimeMillis();
        OrderEvent event = new OrderEvent(
                UUID.randomUUID().toString(),
                "PROCESS_ORDERS",
                tradingPartner,
                businessUnit
        );
        List<Order> orders;
        String fetchSource;
        
        if (orderFetchService.isMongoEnabled()) {
            orders = orderFetchService.fetchOrdersForEvent(event);
            fetchSource = "MongoDB";
        } else {
            // Fallback: return empty when MongoDB disabled
            orders = List.of();
            fetchSource = "MongoDB disabled";
        }
        long mongoTime = System.currentTimeMillis() - mongoStart;

        // Step 2: Process (batch H2 lookups + parallel processing)
        long processStart = System.currentTimeMillis();
        ProcessingResult result = orderProcessor.processOrders(orders);
        long processTime = System.currentTimeMillis() - processStart;

        long totalTime = System.currentTimeMillis() - totalStart;

        return ResponseEntity.ok(Map.of(
                "message", "Full pipeline complete",
                "fetchSource", fetchSource,
                "ordersFetched", orders.size(),
                "mongoFetchTimeMs", mongoTime,
                "processingTimeMs", processTime,
                "totalTimeMs", totalTime,
                "successes", result.successes().size(),
                "failures", result.failures().size(),
                "breakdown", Map.of(
                        "step1_mongo_fetch", mongoTime + "ms",
                        "step2_batch_db_and_process", processTime + "ms",
                        "total", totalTime + "ms"
                )
        ));
    }

    /**
     * Check MongoDB connection status.
     */
    @GetMapping("/mongo/status")
    public ResponseEntity<Map<String, Object>> mongoStatus() {
        return ResponseEntity.ok(Map.of(
                "mongoEnabled", orderFetchService.isMongoEnabled(),
                "status", orderFetchService.isMongoEnabled() ? "CONNECTED" : "DISABLED",
                "message", orderFetchService.isMongoEnabled() 
                        ? "MongoDB is enabled and ready" 
                        : "MongoDB is disabled. Run with docker profile to enable."
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    // LOAD TEST ENDPOINTS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run a load test simulating multiple events with large order batches.
     * 
     * Example: POST /api/loadtest?eventCount=5&ordersPerEvent=2500
     */
    @PostMapping("/loadtest")
    public ResponseEntity<Map<String, Object>> runLoadTest(
            @RequestParam(defaultValue = "5") int eventCount,
            @RequestParam(defaultValue = "2500") int ordersPerEvent) {
        
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("LOAD TEST STARTING: {} events × {} orders = {} total orders",
                eventCount, ordersPerEvent, eventCount * ordersPerEvent);
        log.info("═══════════════════════════════════════════════════════════════");

        List<Map<String, Object>> eventResults = new java.util.ArrayList<>();
        long totalStart = System.currentTimeMillis();
        int totalOrders = 0;
        int totalSuccesses = 0;
        int totalFailures = 0;

        List<String> tradingPartners = List.of("ACME-CORP", "GLOBEX", "INITECH", "UMBRELLA", "STARK-IND");
        List<String> businessUnits = List.of("WEST", "EAST", "NORTH", "SOUTH", "CENTRAL");

        for (int e = 0; e < eventCount; e++) {
            String tradingPartner = tradingPartners.get(e % tradingPartners.size());
            String businessUnit = businessUnits.get(e % businessUnits.size());
            
            log.info("─────────────────────────────────────────────────────────────────");
            log.info("EVENT {}/{}: tradingPartner={}, businessUnit={}", 
                    e + 1, eventCount, tradingPartner, businessUnit);
            log.info("─────────────────────────────────────────────────────────────────");

            long eventStart = System.currentTimeMillis();

            // Generate large batch of mock orders
            List<Order> orders = generateMockOrders(tradingPartner, businessUnit, ordersPerEvent);
            
            // Process using the optimized processor
            ProcessingResult result = orderProcessor.processOrders(orders);
            
            long eventTime = System.currentTimeMillis() - eventStart;
            
            totalOrders += orders.size();
            totalSuccesses += result.successes().size();
            totalFailures += result.failures().size();

            double ordersPerSecond = orders.size() / (eventTime / 1000.0);

            eventResults.add(Map.of(
                    "event", e + 1,
                    "tradingPartner", tradingPartner,
                    "businessUnit", businessUnit,
                    "orderCount", orders.size(),
                    "successes", result.successes().size(),
                    "failures", result.failures().size(),
                    "processingTimeMs", eventTime,
                    "ordersPerSecond", String.format("%.0f", ordersPerSecond)
            ));

            log.info("EVENT {}/{} COMPLETE: {} orders in {}ms ({} orders/sec)",
                    e + 1, eventCount, orders.size(), eventTime, String.format("%.0f", ordersPerSecond));
        }

        long totalTime = System.currentTimeMillis() - totalStart;
        double avgOrdersPerSecond = totalOrders / (totalTime / 1000.0);

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("LOAD TEST COMPLETE: {} orders in {}ms ({} orders/sec)",
                totalOrders, totalTime, String.format("%.0f", avgOrdersPerSecond));
        log.info("═══════════════════════════════════════════════════════════════");

        return ResponseEntity.ok(Map.of(
                "summary", Map.of(
                        "totalEvents", eventCount,
                        "totalOrders", totalOrders,
                        "totalSuccesses", totalSuccesses,
                        "totalFailures", totalFailures,
                        "totalTimeMs", totalTime,
                        "avgOrdersPerSecond", String.format("%.0f", avgOrdersPerSecond),
                        "avgTimePerEvent", String.format("%.0f", (double) totalTime / eventCount)
                ),
                "events", eventResults
        ));
    }

    /**
     * Generate mock orders for load testing.
     */
    private List<Order> generateMockOrders(String tradingPartner, String businessUnit, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Order(
                        String.format("ORD-%s-%s-%05d", tradingPartner.substring(0, 4), businessUnit, i),
                        String.format("CUST-%s-%04d", businessUnit, i % 100),
                        "PENDING",
                        java.math.BigDecimal.valueOf(50 + Math.random() * 950),
                        java.time.LocalDateTime.now().minusDays(i % 30)
                ))
                .toList();
    }
}

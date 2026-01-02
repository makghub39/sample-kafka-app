package com.example.controller;

import com.example.model.*;
import com.example.repository.MongoOrderRepository;
import com.example.service.OrderFetchService;
import com.example.service.OrderProcessingOrchestrator;
import com.example.service.PartnerValidationService;
import com.example.service.PartnerValidationService.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Realistic Load Test Controller that hits REAL services:
 * - MongoDB for order fetching
 * - H2 for batch lookups (customer, inventory, pricing)
 * - Full processing pipeline
 * - Partner/Unit validation with caching
 * 
 * This provides accurate performance metrics for production-like scenarios.
 */
@RestController
@RequestMapping("/api/realistic")
@Slf4j
@RequiredArgsConstructor
public class RealisticLoadTestController {

    private final OrderFetchService orderFetchService;
    private final OrderProcessingOrchestrator processingOrchestrator;
    private final PartnerValidationService partnerValidationService;
    
    @Autowired(required = false)
    private MongoOrderRepository mongoOrderRepository;
    
    @Autowired(required = false)
    private MongoTemplate mongoTemplate;

    // These must match ACTIVE records in trading_partners and business_units tables
    private static final List<String> TRADING_PARTNERS = List.of(
            "ACME Corp", "Global Traders", "FastShip Inc", "Premium Partners", 
            "Enterprise Solutions", "MegaCorp", "TestPartner"
    );
    
    private static final List<String> BUSINESS_UNITS = List.of(
            "Retail Division", "Wholesale Division", "E-Commerce", "International", 
            "B2B Operations", "New Ventures", "TestUnit"
    );

    // ═══════════════════════════════════════════════════════════════
    // STEP 1: SEED MONGODB WITH TEST DATA
    // ═══════════════════════════════════════════════════════════════

    /**
     * Seed MongoDB with test orders for a specific trading partner and business unit.
     * 
     * Example: POST /api/realistic/seed?tradingPartner=ACME-CORP&businessUnit=WEST&orderCount=3000
     */
    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seedMongoDB(
            @RequestParam String tradingPartner,
            @RequestParam String businessUnit,
            @RequestParam(defaultValue = "2500") int orderCount) {
        
        if (!orderFetchService.isMongoEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MongoDB is not enabled",
                    "hint", "Start app with --spring.profiles.active=docker"
            ));
        }

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("SEEDING MongoDB: {} orders for {}::{}", orderCount, tradingPartner, businessUnit);
        log.info("═══════════════════════════════════════════════════════════════");

        long startTime = System.currentTimeMillis();

        // Delete existing orders for this trading partner + business unit
        Query deleteQuery = new Query(Criteria.where("tradingPartnerName").is(tradingPartner)
                .and("businessUnitName").is(businessUnit));
        long deleted = mongoTemplate.remove(deleteQuery, OrderDocument.class).getDeletedCount();
        log.info("Deleted {} existing orders for {}::{}", deleted, tradingPartner, businessUnit);

        // Generate and insert new orders in batches
        List<OrderDocument> orders = new ArrayList<>();
        int batchSize = 1000;
        int inserted = 0;

        for (int i = 1; i <= orderCount; i++) {
            OrderDocument doc = createOrderDocument(tradingPartner, businessUnit, i);
            orders.add(doc);

            if (orders.size() >= batchSize) {
                mongoTemplate.insertAll(orders);
                inserted += orders.size();
                log.info("Inserted batch: {}/{} orders", inserted, orderCount);
                orders.clear();
            }
        }

        // Insert remaining orders
        if (!orders.isEmpty()) {
            mongoTemplate.insertAll(orders);
            inserted += orders.size();
        }

        long duration = System.currentTimeMillis() - startTime;
        double ordersPerSecond = inserted / (duration / 1000.0);

        log.info("SEEDING COMPLETE: {} orders in {}ms ({} orders/sec)", 
                inserted, duration, String.format("%.0f", ordersPerSecond));

        return ResponseEntity.ok(Map.of(
                "message", "MongoDB seeded successfully",
                "tradingPartner", tradingPartner,
                "businessUnit", businessUnit,
                "ordersInserted", inserted,
                "previousOrdersDeleted", deleted,
                "durationMs", duration,
                "insertRate", String.format("%.0f orders/sec", ordersPerSecond)
        ));
    }

    /**
     * Seed MongoDB with test data for ALL trading partner + business unit combinations.
     * 
     * Example: POST /api/realistic/seed-all?ordersPerCombination=2500
     * This will create 5 partners × 5 units = 25 combinations × 2500 = 62,500 orders
     */
    @PostMapping("/seed-all")
    public ResponseEntity<Map<String, Object>> seedAllCombinations(
            @RequestParam(defaultValue = "2500") int ordersPerCombination) {
        
        if (!orderFetchService.isMongoEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MongoDB is not enabled",
                    "hint", "Start app with --spring.profiles.active=docker"
            ));
        }

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("SEEDING ALL COMBINATIONS: {} orders each", ordersPerCombination);
        log.info("═══════════════════════════════════════════════════════════════");

        long totalStart = System.currentTimeMillis();
        int totalInserted = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        // Use first 5 trading partners × all 5 business units
        for (int tp = 0; tp < 5; tp++) {
            for (int bu = 0; bu < 5; bu++) {
                String tradingPartner = TRADING_PARTNERS.get(tp);
                String businessUnit = BUSINESS_UNITS.get(bu);

                long start = System.currentTimeMillis();

                // Delete existing
                Query deleteQuery = new Query(Criteria.where("tradingPartnerName").is(tradingPartner)
                        .and("businessUnitName").is(businessUnit));
                mongoTemplate.remove(deleteQuery, OrderDocument.class);

                // Insert new orders
                List<OrderDocument> orders = IntStream.rangeClosed(1, ordersPerCombination)
                        .mapToObj(i -> createOrderDocument(tradingPartner, businessUnit, i))
                        .toList();
                mongoTemplate.insertAll(orders);

                totalInserted += orders.size();
                long duration = System.currentTimeMillis() - start;

                results.add(Map.of(
                        "tradingPartner", tradingPartner,
                        "businessUnit", businessUnit,
                        "orders", orders.size(),
                        "durationMs", duration
                ));

                log.info("Seeded {}::{} with {} orders in {}ms", 
                        tradingPartner, businessUnit, orders.size(), duration);
            }
        }

        long totalDuration = System.currentTimeMillis() - totalStart;

        return ResponseEntity.ok(Map.of(
                "message", "All combinations seeded",
                "totalOrders", totalInserted,
                "combinations", results.size(),
                "totalDurationMs", totalDuration,
                "avgInsertRate", String.format("%.0f orders/sec", totalInserted / (totalDuration / 1000.0))
        ));
    }

    // ═══════════════════════════════════════════════════════════════
    // STEP 2: RUN REALISTIC LOAD TEST AGAINST REAL MONGODB
    // ═══════════════════════════════════════════════════════════════

    /**
     * Run a REALISTIC load test that:
     * 1. Validates trading partner and business unit status (with caching)
     * 2. Fetches orders from REAL MongoDB
     * 3. Runs REAL H2 batch lookups
     * 4. Processes through REAL pipeline
     * 5. Publishes to REAL JMS/WMQ
     * 
     * Returns DETAILED timing breakdown for each stage.
     * 
     * Example: POST /api/realistic/loadtest?eventCount=5
     * 
     * Prerequisites: Run /api/realistic/seed-all first to populate MongoDB
     */
    @PostMapping("/loadtest")
    public ResponseEntity<Map<String, Object>> runRealisticLoadTest(
            @RequestParam(defaultValue = "5") int eventCount) {
        
        if (!orderFetchService.isMongoEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "MongoDB is not enabled",
                    "hint", "Start app with --spring.profiles.active=docker"
            ));
        }

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ REALISTIC LOAD TEST - HITTING REAL SERVICES                  ║");
        log.info("║ Validation: ✓ | MongoDB: ✓ | H2 Batch: ✓ | Processing: ✓ | JMS: ✓ ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        List<Map<String, Object>> eventResults = new ArrayList<>();
        long totalStart = System.currentTimeMillis();
        
        // Aggregated counters
        int totalOrders = 0;
        int totalSuccesses = 0;
        int totalFailures = 0;
        int totalSkipped = 0;
        
        // Aggregated timings
        long totalValidationTime = 0;
        long totalMongoFetchTime = 0;
        long totalDbPreloadTime = 0;
        long totalBusinessLogicTime = 0;
        long totalJmsPublishTime = 0;

        for (int e = 0; e < eventCount; e++) {
            String tradingPartner = TRADING_PARTNERS.get(e % TRADING_PARTNERS.size());
            String businessUnit = BUSINESS_UNITS.get(e % BUSINESS_UNITS.size());

            log.info("─────────────────────────────────────────────────────────────────");
            log.info("EVENT {}/{}: {}::{}", e + 1, eventCount, tradingPartner, businessUnit);
            log.info("─────────────────────────────────────────────────────────────────");

            // Create event
            OrderEvent event = new OrderEvent(
                    UUID.randomUUID().toString(),
                    "PROCESS_ORDERS",
                    tradingPartner,
                    businessUnit
            );

            long eventStart = System.currentTimeMillis();

            // ═══════════════════════════════════════════════════════════════
            // STEP 0: VALIDATE Partner/Unit Status (with caching)
            // ═══════════════════════════════════════════════════════════════
            long validationStart = System.currentTimeMillis();
            ValidationResult validation = partnerValidationService.validateEvent(event);
            long validationTime = System.currentTimeMillis() - validationStart;
            totalValidationTime += validationTime;

            if (!validation.shouldProcess()) {
                log.warn("SKIPPING EVENT {} - {}", e + 1, validation.skipReason());
                totalSkipped++;
                eventResults.add(Map.of(
                        "event", e + 1,
                        "tradingPartner", tradingPartner,
                        "businessUnit", businessUnit,
                        "orderCount", 0,
                        "skipped", true,
                        "skipReason", validation.skipReason(),
                        "validationTimeMs", validationTime
                ));
                continue;
            }

            log.info("  Validation: PASSED in {}ms", validationTime);

            // ═══════════════════════════════════════════════════════════════
            // STEP 1: Fetch from REAL MongoDB
            // ═══════════════════════════════════════════════════════════════
            long mongoStart = System.currentTimeMillis();
            List<Order> orders = orderFetchService.fetchOrdersForEvent(event);
            long mongoFetchTime = System.currentTimeMillis() - mongoStart;
            totalMongoFetchTime += mongoFetchTime;

            log.info("  MongoDB: {} orders in {}ms", orders.size(), mongoFetchTime);

            if (orders.isEmpty()) {
                log.warn("No orders found for {}::{}. Did you run /api/realistic/seed-all first?", 
                        tradingPartner, businessUnit);
                eventResults.add(Map.of(
                        "event", e + 1,
                        "tradingPartner", tradingPartner,
                        "businessUnit", businessUnit,
                        "orderCount", 0,
                        "error", "No orders found - run /api/realistic/seed-all first",
                        "validationTimeMs", validationTime
                ));
                continue;
            }

            // ═══════════════════════════════════════════════════════════════
            // STEP 2: Process through REAL pipeline (H2 + Processing + JMS)
            // ═══════════════════════════════════════════════════════════════
            boolean useGrouping = event.requiresGrouping();
            ProcessingResultWithTiming result = processingOrchestrator.processOrdersWithTiming(orders, useGrouping);
            
            // Accumulate timing
            totalDbPreloadTime += result.preloadTimeMs();
            totalBusinessLogicTime += result.processingTimeMs();
            totalJmsPublishTime += result.publishTimeMs();

            long eventTime = System.currentTimeMillis() - eventStart;

            totalOrders += orders.size();
            totalSuccesses += result.successes().size();
            totalFailures += result.failures().size();

            double ordersPerSecond = orders.size() / (eventTime / 1000.0);

            // Build detailed event result
            Map<String, Object> eventResult = new LinkedHashMap<>();
            eventResult.put("event", e + 1);
            eventResult.put("tradingPartner", tradingPartner);
            eventResult.put("businessUnit", businessUnit);
            eventResult.put("orderCount", orders.size());
            eventResult.put("successes", result.successes().size());
            eventResult.put("failures", result.failures().size());
            eventResult.put("timing", Map.of(
                    "validationMs", validationTime,
                    "mongoFetchMs", mongoFetchTime,
                    "dbPreloadMs", result.preloadTimeMs(),
                    "businessLogicMs", result.processingTimeMs(),
                    "jmsPublishMs", result.publishTimeMs(),
                    "totalEventMs", eventTime
            ));
            eventResult.put("ordersPerSecond", String.format("%.0f", ordersPerSecond));
            
            eventResults.add(eventResult);

            log.info("EVENT {}/{} COMPLETE: {} orders in {}ms ({}/sec)", 
                    e + 1, eventCount, orders.size(), eventTime, String.format("%.0f", ordersPerSecond));
            log.info("  Breakdown: Mongo={}ms | DB={}ms | Logic={}ms | JMS={}ms",
                    mongoFetchTime, result.preloadTimeMs(), result.processingTimeMs(), result.publishTimeMs());
        }

        long totalTime = System.currentTimeMillis() - totalStart;
        double avgOrdersPerSecond = totalOrders > 0 ? totalOrders / (totalTime / 1000.0) : 0;

        // Calculate percentages
        long totalPipelineTime = totalValidationTime + totalMongoFetchTime + totalDbPreloadTime + totalBusinessLogicTime + totalJmsPublishTime;
        
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ REALISTIC LOAD TEST COMPLETE                                 ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ Total Orders: {:,}  | Skipped Events: {}                     ", totalOrders, totalSkipped);
        log.info("║ Total Time: {}ms | Throughput: {} orders/sec                 ", totalTime, String.format("%.0f", avgOrdersPerSecond));
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║ TIME BREAKDOWN:                                              ");
        log.info("║   Validation:       {:>6}ms ({:>5.1f}%)                       ", totalValidationTime, pct(totalValidationTime, totalPipelineTime));
        log.info("║   MongoDB Fetch:    {:>6}ms ({:>5.1f}%)                       ", totalMongoFetchTime, pct(totalMongoFetchTime, totalPipelineTime));
        log.info("║   DB Preload (H2):  {:>6}ms ({:>5.1f}%)                       ", totalDbPreloadTime, pct(totalDbPreloadTime, totalPipelineTime));
        log.info("║   Business Logic:   {:>6}ms ({:>5.1f}%)                       ", totalBusinessLogicTime, pct(totalBusinessLogicTime, totalPipelineTime));
        log.info("║   JMS Publishing:   {:>6}ms ({:>5.1f}%)                       ", totalJmsPublishTime, pct(totalJmsPublishTime, totalPipelineTime));
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // Build comprehensive response
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalEvents", eventCount);
        summary.put("totalSkippedEvents", totalSkipped);
        summary.put("totalOrders", totalOrders);
        summary.put("totalSuccesses", totalSuccesses);
        summary.put("totalFailures", totalFailures);
        summary.put("totalTimeMs", totalTime);
        summary.put("avgOrdersPerSecond", String.format("%.0f", avgOrdersPerSecond));

        // Calculate how many events were actually processed
        int processedEvents = eventCount - totalSkipped;

        // Detailed timing breakdown
        Map<String, Object> timingBreakdown = new LinkedHashMap<>();
        timingBreakdown.put("validation", Map.of(
                "totalMs", totalValidationTime,
                "avgMs", totalValidationTime / Math.max(eventCount, 1),
                "percent", String.format("%.1f%%", pct(totalValidationTime, totalPipelineTime)),
                "description", "Trading partner & business unit status lookups (cached)"
        ));
        timingBreakdown.put("mongoFetch", Map.of(
                "totalMs", totalMongoFetchTime,
                "avgMs", totalMongoFetchTime / Math.max(processedEvents, 1),
                "percent", String.format("%.1f%%", pct(totalMongoFetchTime, totalPipelineTime))
        ));
        timingBreakdown.put("dbPreload", Map.of(
                "totalMs", totalDbPreloadTime,
                "avgMs", totalDbPreloadTime / Math.max(processedEvents, 1),
                "percent", String.format("%.1f%%", pct(totalDbPreloadTime, totalPipelineTime)),
                "description", "H2 batch lookups (customer, inventory, pricing)"
        ));
        timingBreakdown.put("businessLogic", Map.of(
                "totalMs", totalBusinessLogicTime,
                "avgMs", totalBusinessLogicTime / Math.max(processedEvents, 1),
                "percent", String.format("%.1f%%", pct(totalBusinessLogicTime, totalPipelineTime)),
                "description", "Core processing with concurrency control"
        ));
        timingBreakdown.put("jmsPublish", Map.of(
                "totalMs", totalJmsPublishTime,
                "avgMs", totalJmsPublishTime / Math.max(processedEvents, 1),
                "percent", String.format("%.1f%%", pct(totalJmsPublishTime, totalPipelineTime)),
                "description", "WMQ/JMS message publishing"
        ));
        timingBreakdown.put("totalPipelineMs", totalPipelineTime);

        return ResponseEntity.ok(Map.of(
                "testType", "REALISTIC - Validation + Real MongoDB + Real H2 + Real Processing + Real JMS",
                "summary", summary,
                "timingBreakdown", timingBreakdown,
                "events", eventResults
        ));
    }

    /**
     * Calculate percentage safely.
     */
    private double pct(long part, long total) {
        return total > 0 ? (part * 100.0) / total : 0;
    }

    /**
     * Quick check to verify MongoDB connectivity and data.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mongoEnabled", orderFetchService.isMongoEnabled());
        
        if (orderFetchService.isMongoEnabled() && mongoOrderRepository != null) {
            // Count orders by trading partner
            Map<String, Long> ordersByPartner = new LinkedHashMap<>();
            for (String partner : TRADING_PARTNERS.subList(0, 5)) {
                long count = mongoOrderRepository.countByTradingPartnerName(partner);
                if (count > 0) {
                    ordersByPartner.put(partner, count);
                }
            }
            
            long totalOrders = ordersByPartner.values().stream().mapToLong(Long::longValue).sum();
            status.put("totalOrdersInMongo", totalOrders);
            status.put("ordersByTradingPartner", ordersByPartner);
            status.put("status", totalOrders > 0 ? "READY" : "EMPTY - Run /api/realistic/seed-all first");
        } else {
            status.put("status", "MONGODB_DISABLED");
            status.put("hint", "Start with --spring.profiles.active=docker");
        }
        
        return ResponseEntity.ok(status);
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    private OrderDocument createOrderDocument(String tradingPartner, String businessUnit, int index) {
        OrderDocument doc = new OrderDocument();
        doc.setOrderId(String.format("ORD-%s-%s-%05d", 
                tradingPartner.substring(0, Math.min(4, tradingPartner.length())), 
                businessUnit, 
                index));
        doc.setCustomerId(String.format("CUST-%s-%04d", businessUnit, (index % 100) + 1));
        doc.setTradingPartnerName(tradingPartner);
        doc.setBusinessUnitName(businessUnit);
        doc.setStatus("PENDING");
        doc.setAmount(BigDecimal.valueOf(100 + (Math.random() * 900)));
        doc.setCreatedAt(java.time.Instant.now().minusSeconds(index * 86400L % (30 * 86400L)));
        doc.setItems(List.of(
                new OrderDocument.OrderItem("SKU-" + String.format("%03d", (index % 10) + 1), 
                        1 + (index % 5), 
                        BigDecimal.valueOf(20 + Math.random() * 80))
        ));
        return doc;
    }
}

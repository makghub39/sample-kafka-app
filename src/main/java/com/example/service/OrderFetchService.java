package com.example.service;

import com.example.config.AppMetrics;
import com.example.model.Order;
import com.example.model.OrderDocument;
import com.example.model.OrderEvent;
import com.example.repository.MongoOrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for fetching orders from MongoDB.
 * This replaces the external API call in the original architecture.
 * 
 * Original flow: Kafka Event -> API Call (HTTP, slow, ~100-500ms) -> Orders
 * Optimized flow: Kafka Event -> MongoDB Query (~5-20ms) -> Orders
 * 
 * MongoDB provides:
 * - Much lower latency than HTTP API calls
 * - Better batch query support with $in operator
 * - Connection pooling built-in
 * - No network overhead of HTTP
 * 
 * Note: MongoDB is optional. When disabled, mock orders are returned.
 */
@Slf4j
@Service
public class OrderFetchService {

    @Autowired(required = false)
    private MongoOrderRepository mongoOrderRepository;
    
    @Autowired(required = false)
    private MongoTemplate mongoTemplate;
    
    @Autowired
    private AppMetrics metrics;

    @Value("${app.mongodb.enabled:false}")
    private boolean mongoEnabled;

    /**
     * Fetch orders based on the Kafka event.
     * The event contains information to query relevant orders.
     */
    public List<Order> fetchOrdersForEvent(OrderEvent event) {
        if (!mongoEnabled || mongoOrderRepository == null) {
            log.debug("MongoDB is disabled or not available, returning mock orders");
            return createMockOrders(event);
        }

        log.debug("Fetching orders from MongoDB for event: {}", event.eventId());
        long startTime = System.currentTimeMillis();

        try {
            List<OrderDocument> documents = fetchOrderDocuments(event);
            
            List<Order> orders = documents.stream()
                    .map(OrderDocument::toOrder)
                    .collect(Collectors.toList());

            long duration = System.currentTimeMillis() - startTime;
            metrics.recordMongoDbFetchTime(duration);  // Record MongoDB timing
            log.info("Fetched {} orders from MongoDB in {}ms for event: {}", 
                    orders.size(), duration, event.eventId());

            return orders;
        } catch (Exception e) {
            log.error("Error fetching orders from MongoDB: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch orders from MongoDB", e);
        }
    }

    /**
     * Fetch order documents based on event type and data.
     * Supports multiple strategies based on what's in the event.
     */
    private List<OrderDocument> fetchOrderDocuments(OrderEvent event) {
        String tradingPartner = event.tradingPartnerName();
        String businessUnit = event.businessUnitName();

        // Strategy 1: Fetch by both trading partner and business unit
        if (hasValue(tradingPartner) && hasValue(businessUnit)) {
            log.debug("Fetching by trading partner: {} and business unit: {}", 
                    tradingPartner, businessUnit);
            return mongoOrderRepository.findByTradingPartnerNameAndBusinessUnitNameAndStatus(
                    tradingPartner, businessUnit, "PENDING");
        }

        // Strategy 2: Fetch by trading partner only
        if (hasValue(tradingPartner)) {
            log.debug("Fetching by trading partner: {}", tradingPartner);
            return mongoOrderRepository.findByTradingPartnerNameAndStatus(
                    tradingPartner, "PENDING");
        }

        // Strategy 3: Fetch by business unit only
        if (hasValue(businessUnit)) {
            log.debug("Fetching by business unit: {}", businessUnit);
            return mongoOrderRepository.findByBusinessUnitNameAndStatus(
                    businessUnit, "PENDING");
        }

        // Strategy 4: Fetch pending orders (default batch processing)
        log.debug("Fetching pending orders batch");
        return mongoOrderRepository.findTop100ByStatusOrderByCreatedAtAsc("PENDING");
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Batch fetch orders by multiple order IDs.
     * Uses MongoDB $in operator for efficient batch query.
     */
    public List<Order> fetchOrdersByIds(List<String> orderIds) {
        if (!mongoEnabled || mongoOrderRepository == null || orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyList();
        }

        long startTime = System.currentTimeMillis();
        List<Order> orders = mongoOrderRepository.findByOrderIdIn(orderIds).stream()
                .map(OrderDocument::toOrder)
                .collect(Collectors.toList());
        
        log.debug("Fetched {} orders by IDs in {}ms", orders.size(), 
                System.currentTimeMillis() - startTime);
        return orders;
    }

    /**
     * Update order status in MongoDB after processing.
     */
    public void updateOrderStatus(String orderId, String newStatus) {
        if (!mongoEnabled || mongoTemplate == null) {
            return;
        }

        Query query = new Query(Criteria.where("orderId").is(orderId));
        Update update = new Update().set("status", newStatus);
        mongoTemplate.updateFirst(query, update, OrderDocument.class);
        
        log.debug("Updated order {} status to {}", orderId, newStatus);
    }

    /**
     * Batch update order statuses - more efficient than individual updates.
     */
    public void batchUpdateOrderStatus(List<String> orderIds, String newStatus) {
        if (!mongoEnabled || mongoTemplate == null || orderIds == null || orderIds.isEmpty()) {
            return;
        }

        Query query = new Query(Criteria.where("orderId").in(orderIds));
        Update update = new Update().set("status", newStatus);
        var result = mongoTemplate.updateMulti(query, update, OrderDocument.class);
        
        log.info("Batch updated {} orders to status {}", result.getModifiedCount(), newStatus);
    }

    /**
     * Check if MongoDB is enabled and accessible.
     */
    public boolean isMongoEnabled() {
        return mongoEnabled && mongoOrderRepository != null;
    }

    /**
     * Create mock orders when MongoDB is disabled (for testing without MongoDB).
     */
    private List<Order> createMockOrders(OrderEvent event) {
        int count = 5; // Default mock count
        String tradingPartner = event.tradingPartnerName();
        String businessUnit = event.businessUnitName();
        
        // Generate mock orders based on trading partner and business unit
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new Order(
                        "ORD-" + Math.abs(tradingPartner.hashCode()) % 1000 + "-" + i,
                        "CUST-" + businessUnit + "-" + i,
                        "PENDING",
                        java.math.BigDecimal.valueOf(100 + Math.random() * 900),
                        java.time.LocalDateTime.now()
                ))
                .collect(Collectors.toList());
    }
}

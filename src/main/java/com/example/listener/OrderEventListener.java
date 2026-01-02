package com.example.listener;

import com.example.config.AppMetrics;
import com.example.model.*;
import com.example.service.DeadLetterPublisher;
import com.example.service.OrderFetchService;
import com.example.service.OrderProcessor;
import com.example.service.WmqPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
// import org.springframework.kafka.annotation.KafkaListener;
// import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * DEPRECATED: Replaced by Apache Camel route (OrderEventRoute.java)
 * 
 * Kafka Listener with SINGLE EVENT processing and manual acknowledgment.
 * Kept for reference - the @KafkaListener annotation is commented out.
 * 
 * To switch back to Spring Kafka listener:
 * 1. Uncomment the @KafkaListener annotation below
 * 2. Re-enable spring-kafka dependency in pom.xml
 * 3. Set camel.route.autostart=false in application.yml
 * 4. Set spring.kafka.listener.auto-startup=true
 * 
 * Optimized Flow (per event):
 * 1. Receive single Kafka event (contains multiple order IDs)
 * 2. Fetch all orders for this event from MongoDB
 * 3. PREFETCH: Batch DB lookups for all orders (3 queries total)
 * 4. PARALLEL: Process all orders with virtual threads
 * 5. Send to WMQ
 * 6. Commit Kafka offset (per event - lower risk)
 * 
 * Benefits over batch processing:
 * - Lower risk: failure affects only 1 event, not 500
 * - Per-event commit: finer granularity
 * - Still has prefetch optimization: 3 DB queries for all orders in event
 */
// @Component  // Disabled - using Camel route instead
@Slf4j
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderFetchService orderFetchService;  // MongoDB - replaces API call
    private final OrderProcessor orderProcessor;
    private final WmqPublisher wmqPublisher;
    private final DeadLetterPublisher deadLetterPublisher;
    private final AppMetrics metrics;

    /**
     * DISABLED: Now using Apache Camel route instead.
     * Single event Kafka listener with manual acknowledgment.
     * Processes one event at a time, commits after each event.
     */
    // @KafkaListener(
    //         topics = "${app.kafka.topic.order-events:order-events}",
    //         groupId = "${spring.kafka.consumer.group-id}",
    //         containerFactory = "kafkaListenerContainerFactory"
    // )
    public void consumeEvent(OrderEvent event /*, Acknowledgment ack */) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ Received EVENT: {} | Type: {}                                 ", 
                event.eventId(), event.eventType());
        log.info("║ Trading Partner: {} | Business Unit: {}                       ", 
                event.tradingPartnerName(), event.businessUnitName());
        log.info("╚══════════════════════════════════════════════════════════════╝");
        
        metrics.incrementKafkaBatch();  // Reusing counter for events

        long startTime = System.currentTimeMillis();

        try {
            // ═══════════════════════════════════════════════════════════
            // STEP 1: Fetch orders from MongoDB for this event
            // ═══════════════════════════════════════════════════════════
            long mongoStart = System.currentTimeMillis();
            List<Order> orders = orderFetchService.fetchOrdersForEvent(event);
            
            log.info("Fetched {} orders from MongoDB in {}ms", 
                    orders.size(), System.currentTimeMillis() - mongoStart);

            if (orders.isEmpty()) {
                // ack.acknowledge();  // Disabled - class is deprecated
                log.info("No orders to process, acknowledged offset");
                return;
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 2: Process all orders for this event
            // - PREFETCH: 3 batch DB queries for all orders
            // - PARALLEL: Process orders with virtual threads
            // - WMQ: Publish results
            // ═══════════════════════════════════════════════════════════
            boolean useGrouping = event.requiresGrouping();
            log.info("WMQ publishing mode: {} (event type: {})", 
                    useGrouping ? "GROUPED" : "INDIVIDUAL", event.eventType());
            
            ProcessingResult result = orderProcessor.processOrders(orders, useGrouping);

            // ═══════════════════════════════════════════════════════════
            // STEP 3: Send failures to DLQ
            // ═══════════════════════════════════════════════════════════
            if (!result.failures().isEmpty()) {
                deadLetterPublisher.send(result.failures());
            }

            // ═══════════════════════════════════════════════════════════
            // STEP 4: Update order status in MongoDB
            // ═══════════════════════════════════════════════════════════
            List<String> processedIds = result.successes().stream()
                    .map(ProcessedOrder::orderId)
                    .toList();
            orderFetchService.batchUpdateOrderStatus(processedIds, "PROCESSING");

            // ═══════════════════════════════════════════════════════════
            // STEP 5: Commit offset for THIS EVENT only
            // ═══════════════════════════════════════════════════════════
            // ack.acknowledge();  // Disabled - class is deprecated

            long totalTime = System.currentTimeMillis() - startTime;
            
            // Record metrics
            metrics.recordTotalEventTime(totalTime);
            metrics.incrementOrdersProcessed(
                    result.successes().size() + result.failures().size(),
                    result.successes().size(),
                    result.failures().size()
            );
            
            log.info("╔══════════════════════════════════════════════════════════════╗");
            log.info("║ EVENT COMPLETE - Committed offset                            ║");
            log.info("║ Event: {} | Total: {}ms | Success: {} | Failed: {}           ", 
                    event.eventId(), totalTime, result.successes().size(), result.failures().size());
            log.info("╚══════════════════════════════════════════════════════════════╝");

        } catch (Exception e) {
            log.error("╔══════════════════════════════════════════════════════════════╗");
            log.error("║ EVENT FAILED - NOT committing offset                         ║");
            log.error("║ Event: {} | Error: {}                                        ", 
                    event.eventId(), e.getMessage());
            log.error("╚══════════════════════════════════════════════════════════════╝", e);
            // Don't acknowledge - Kafka will redeliver this event
            throw e;
        }
    }
}

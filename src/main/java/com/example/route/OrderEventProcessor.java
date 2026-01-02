package com.example.route;

import com.example.config.AppMetrics;
import com.example.model.*;
import com.example.service.DeadLetterPublisher;
import com.example.service.OrderFetchService;
import com.example.service.OrderProcessingOrchestrator;
import com.example.service.PartnerValidationService;
import com.example.service.PartnerValidationService.ValidationResult;
import com.example.service.cache.EventDeduplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.component.kafka.KafkaConstants;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Camel Processor for OrderEvent messages.
 * 
 * This processor is intentionally THIN - it only:
 * 1. Extracts the event from Camel exchange
 * 2. Validates partner/unit status (cached)
 * 3. Delegates to focused services
 * 4. Handles Kafka offset commit
 * 
 * All business logic is in the service layer:
 * - PartnerValidationService   → Trading partner/business unit validation
 * - OrderFetchService          → MongoDB order fetching
 * - OrderProcessingOrchestrator → Orchestrates preload → process → publish
 * - DeadLetterPublisher        → DLQ handling
 */
@Component("orderEventProcessor")
@Slf4j
@RequiredArgsConstructor
public class OrderEventProcessor implements Processor {

    private final OrderFetchService orderFetchService;
    private final OrderProcessingOrchestrator processingOrchestrator;
    private final DeadLetterPublisher deadLetterPublisher;
    private final EventDeduplicationService deduplicationService;
    private final PartnerValidationService partnerValidationService;
    private final AppMetrics metrics;

    @Override
    public void process(Exchange exchange) throws Exception {
        OrderEvent event = exchange.getIn().getBody(OrderEvent.class);
        log.info("Received Order Event: {}, exchangeid: {}", event, exchange.getExchangeId());
        // ═══════════════════════════════════════════════════════════════
        // DEDUPLICATION CHECK - Skip if already processed
        // Uses tradingPartnerName + businessUnitName as cache key
        // ═══════════════════════════════════════════════════════════════
        if (!deduplicationService.tryAcquire(event)) {
            log.warn("Skipping duplicate event for tradingPartner={}, businessUnit={}", 
                    event.tradingPartnerName(), event.businessUnitName());
            metrics.incrementDuplicateEvents();
            commitKafkaOffset(exchange);  // Still commit to avoid reprocessing
            return;
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PARTNER/UNIT VALIDATION - Skip if both inactive (cached lookups)
        // Single DB calls per partner/unit, cached for 10 minutes
        // ═══════════════════════════════════════════════════════════════
        ValidationResult validation = partnerValidationService.validateEvent(event);
//        if (!validation.shouldProcess()) {
//            log.warn("Skipping event {} - {}", event.eventId(), validation.skipReason());
//            metrics.incrementSkippedEvents();
//            commitKafkaOffset(exchange);  // Commit to avoid reprocessing
//            return;
//        }
        
        logEventReceived(event);
        metrics.incrementKafkaBatch();
        
        long startTime = System.currentTimeMillis();

        try {
            // STEP 1: Fetch orders from MongoDB
            List<Order> orders = fetchOrders(event);
            
            if (orders.isEmpty()) {
                commitKafkaOffset(exchange);
                log.info("No orders to process, committed Kafka offset");
                return;
            }

            // STEP 2: Process orders (preload → business logic → publish)
            boolean useGrouping = event.requiresGrouping();
            ProcessingResult result = processingOrchestrator.processOrders(orders, useGrouping);

            // STEP 3: Handle failures
            if (!result.failures().isEmpty()) {
                deadLetterPublisher.send(result.failures());
            }

            // STEP 4: Commit Kafka offset
            commitKafkaOffset(exchange);
            
            // Record metrics and log completion
            recordCompletion(event, result, startTime, exchange);
            
        } catch (Exception e) {
            log.error("Failed to process event {}: {}", event.eventId(), e.getMessage(), e);
            throw e; // Re-throw for Camel error handler
        }
    }

    private List<Order> fetchOrders(OrderEvent event) {
        long mongoStart = System.currentTimeMillis();
        List<Order> orders = orderFetchService.fetchOrdersForEvent(event);
        log.info("Fetched {} orders from MongoDB in {}ms", 
                orders.size(), System.currentTimeMillis() - mongoStart);
        return orders;
    }

    private void recordCompletion(OrderEvent event, ProcessingResult result, 
                                   long startTime, Exchange exchange) {
        long totalTime = System.currentTimeMillis() - startTime;
        metrics.getTotalEventTimer().record(totalTime, TimeUnit.MILLISECONDS);
        exchange.getIn().setHeader("eventId", event.eventId());
        
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ EVENT COMPLETE: {} in {}ms                                    ", event.eventId(), totalTime);
        log.info("║ Success: {} | Failed: {}                                      ", 
                result.successes().size(), result.failures().size());
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    private void logEventReceived(OrderEvent event) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ Processing EVENT: {} | Type: {}                               ", 
                event.eventId(), event.eventType());
        log.info("║ Trading Partner: {} | Business Unit: {}                       ", 
                event.tradingPartnerName(), event.businessUnitName());
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    private void commitKafkaOffset(Exchange exchange) {
        Object manualCommit = exchange.getIn().getHeader(KafkaConstants.MANUAL_COMMIT);
        if (manualCommit != null) {
            try {
                manualCommit.getClass().getMethod("commit").invoke(manualCommit);
                log.debug("Kafka offset committed successfully");
            } catch (Exception e) {
                log.warn("Failed to commit Kafka offset: {}", e.getMessage());
            }
        } else {
            log.warn("Manual commit header not found - auto-commit may be enabled");
        }
    }
}

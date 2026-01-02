package com.example.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Application metrics for monitoring performance.
 * 
 * Tracks:
 * - Total orders processed (success + failure)
 * - Success count
 * - Failure count
 * - Total processing time
 * - Avg DB call time (H2 batch queries)
 * - Avg MongoDB call time
 * 
 * View at: http://localhost:8080/actuator/metrics
 * 
 * Key metrics:
 * - order.total.count     → Total orders processed
 * - order.success         → Successful orders
 * - order.failed          → Failed orders
 * - order.processing.time → Total processing time (use TOTAL_TIME)
 * - order.db.fetch        → H2 DB call timing (use MEAN for avg)
 * - order.mongodb.fetch   → MongoDB call timing (use MEAN for avg)
 */
@Component
@Getter
public class AppMetrics {

    // Timers (track count, total time, max, mean)
    private final Timer dbFetchTimer;           // H2 batch DB calls
    private final Timer mongoDbFetchTimer;      // MongoDB order fetch
    private final Timer processingTimer;        // Order processing
    private final Timer wmqPublishTimer;        // WMQ publishing
    private final Timer totalEventTimer;        // Total event processing time

    // Counters
    private final Counter ordersSuccessCounter;
    private final Counter ordersFailedCounter;
    private final Counter ordersTotalCounter;
    private final Counter eventsReceivedCounter;
    private final Counter wmqMessagesCounter;
    private final Counter duplicateEventsCounter;
    private final Counter skippedEventsCounter;
    private final Counter cacheHitsCounter;
    private final Counter cacheMissesCounter;

    public AppMetrics(MeterRegistry registry) {
        // ═══════════════════════════════════════════════════════════════
        // TIMERS - Track latency (count, total, max, mean)
        // ═══════════════════════════════════════════════════════════════
        
        this.dbFetchTimer = Timer.builder("order.db.fetch")
                .description("H2 database batch query time (3 parallel queries)")
                .tag("database", "h2")
                .register(registry);

        this.mongoDbFetchTimer = Timer.builder("order.mongodb.fetch")
                .description("MongoDB order fetch time")
                .tag("database", "mongodb")
                .register(registry);

        this.processingTimer = Timer.builder("order.processing.time")
                .description("Order processing time (business logic)")
                .register(registry);

        this.wmqPublishTimer = Timer.builder("order.wmq.publish")
                .description("WMQ publish time")
                .register(registry);

        this.totalEventTimer = Timer.builder("order.event.total")
                .description("Total end-to-end event processing time")
                .register(registry);

        // ═══════════════════════════════════════════════════════════════
        // COUNTERS - Track counts
        // ═══════════════════════════════════════════════════════════════
        
        this.ordersTotalCounter = Counter.builder("order.total.count")
                .description("Total orders processed (success + failed)")
                .register(registry);

        this.ordersSuccessCounter = Counter.builder("order.success")
                .description("Successfully processed orders")
                .register(registry);

        this.ordersFailedCounter = Counter.builder("order.failed")
                .description("Failed orders")
                .register(registry);

        this.eventsReceivedCounter = Counter.builder("event.received")
                .description("Kafka events received")
                .register(registry);

        this.wmqMessagesCounter = Counter.builder("wmq.messages.sent")
                .description("WMQ messages sent")
                .register(registry);

        this.duplicateEventsCounter = Counter.builder("event.duplicate")
                .description("Duplicate events skipped (deduplication)")
                .register(registry);

        this.skippedEventsCounter = Counter.builder("event.skipped.inactive")
                .description("Events skipped due to inactive trading partner/business unit")
                .register(registry);

        this.cacheHitsCounter = Counter.builder("cache.hits")
                .description("Cache hits for preloaded data")
                .register(registry);

        this.cacheMissesCounter = Counter.builder("cache.misses")
                .description("Cache misses requiring DB fetch")
                .register(registry);
    }

    // ═══════════════════════════════════════════════════════════════
    // TIMING METHODS
    // ═══════════════════════════════════════════════════════════════

    public void recordDbFetchTime(long millis) {
        dbFetchTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordMongoDbFetchTime(long millis) {
        mongoDbFetchTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordProcessingTime(long millis) {
        processingTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordWmqPublishTime(long millis) {
        wmqPublishTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordTotalEventTime(long millis) {
        totalEventTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    // ═══════════════════════════════════════════════════════════════
    // COUNTER METHODS
    // ═══════════════════════════════════════════════════════════════

    public void incrementOrdersProcessed(int total, int success, int failed) {
        ordersTotalCounter.increment(total);
        ordersSuccessCounter.increment(success);
        ordersFailedCounter.increment(failed);
    }

    public void incrementEventReceived() {
        eventsReceivedCounter.increment();
    }

    public void incrementWmqMessages(int count) {
        wmqMessagesCounter.increment(count);
    }

    // ═══════════════════════════════════════════════════════════════
    // LEGACY METHODS (for backward compatibility)
    // ═══════════════════════════════════════════════════════════════
    
    public Timer getDbFetchTimer() {
        return dbFetchTimer;
    }
    
    public Timer getProcessingTimer() {
        return processingTimer;
    }
    
    public Timer getWmqPublishTimer() {
        return wmqPublishTimer;
    }
    
    public Timer getTotalProcessingTimer() {
        return totalEventTimer;
    }
    
    public void incrementOrdersProcessed(int count) {
        ordersSuccessCounter.increment(count);
        ordersTotalCounter.increment(count);
    }
    
    public void incrementOrdersFailed(int count) {
        ordersFailedCounter.increment(count);
        ordersTotalCounter.increment(count);
    }
    
    public void incrementKafkaBatch() {
        eventsReceivedCounter.increment();
    }
    
    public void incrementDuplicateEvents() {
        duplicateEventsCounter.increment();
    }
    
    public void incrementSkippedEvents() {
        skippedEventsCounter.increment();
    }
    
    public void incrementCacheHits(int count) {
        cacheHitsCounter.increment(count);
    }
    
    public void incrementCacheMisses(int count) {
        cacheMissesCounter.increment(count);
    }
}

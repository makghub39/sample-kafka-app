package com.example.service.cache;

import com.example.model.OrderEvent;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for preventing duplicate event processing (idempotency).
 * 
 * Uses a Caffeine cache to track processed trading partner + business unit combinations.
 * Events for the same trading partner and business unit that have already been 
 * processed within the TTL window are skipped.
 * 
 * Cache TTL ensures we don't run out of memory for long-running apps,
 * while still covering typical Kafka rebalance/retry windows.
 * 
 * Thread-safe: Caffeine caches are inherently thread-safe.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EventDeduplicationService {

    private final Cache<String, Long> eventDeduplicationCache;

    /**
     * Generate a deduplication key from trading partner and business unit.
     */
    private String generateKey(String tradingPartnerName, String businessUnitName) {
        return tradingPartnerName + "::" + businessUnitName;
    }

    /**
     * Generate a deduplication key from an OrderEvent.
     */
    private String generateKey(OrderEvent event) {
        return generateKey(event.tradingPartnerName(), event.businessUnitName());
    }

    /**
     * Check if an event has already been processed.
     * 
     * @param event The order event
     * @return true if this is a duplicate event (already processed)
     */
    public boolean isDuplicate(OrderEvent event) {
        return eventDeduplicationCache.getIfPresent(generateKey(event)) != null;
    }

    /**
     * Mark an event as processed.
     * Call this AFTER successfully processing and committing the event.
     * 
     * @param event The order event
     */
    public void markProcessed(OrderEvent event) {
        String key = generateKey(event);
        eventDeduplicationCache.put(key, System.currentTimeMillis());
        log.debug("Marked event as processed: tradingPartner={}, businessUnit={}", 
                event.tradingPartnerName(), event.businessUnitName());
    }

    /**
     * Check and mark atomically - returns true if event should be processed.
     * 
     * Uses putIfAbsent semantics:
     * - If key not in cache: adds it and returns true (process it)
     * - If key already in cache: returns false (skip it)
     * 
     * @param event The order event
     * @return true if event should be processed, false if duplicate
     */
    public boolean tryAcquire(OrderEvent event) {
        String key = generateKey(event);
        Long existingTimestamp = eventDeduplicationCache.asMap()
                .putIfAbsent(key, System.currentTimeMillis());
        
        if (existingTimestamp != null) {
            log.warn("DUPLICATE EVENT DETECTED: tradingPartner={}, businessUnit={} (originally processed at {})", 
                    event.tradingPartnerName(), event.businessUnitName(), existingTimestamp);
            return false; // Duplicate - don't process
        }
        
        return true; // New event - proceed with processing
    }

    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getStats() {
        var stats = eventDeduplicationCache.stats();
        return new CacheStats(
                eventDeduplicationCache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate()
        );
    }

    /**
     * Cache statistics record.
     */
    public record CacheStats(
            long size,
            long hits,
            long misses,
            double hitRate
    ) {}
}

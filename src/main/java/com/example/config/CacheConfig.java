package com.example.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.model.CustomerData;
import com.example.model.InventoryData;
import com.example.model.PricingData;
import com.example.model.TradingPartnerStatus;
import com.example.model.BusinessUnitStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine cache configuration for order processing.
 * 
 * Two caches:
 * 1. DATA CACHE - Caches preloaded customer/inventory/pricing data
 *    - Reduces redundant DB calls for recently processed orders
 *    - TTL: 5 minutes (data can change, don't cache too long)
 * 
 * 2. EVENT DEDUPLICATION CACHE - Prevents duplicate event processing
 *    - Stores event IDs that have been processed
 *    - TTL: 1 hour (covers Kafka rebalance/retry scenarios)
 *    - Provides idempotency guarantee
 */
@Configuration
@Slf4j
public class CacheConfig {

    // ═══════════════════════════════════════════════════════════════
    // DATA CACHE SETTINGS
    // ═══════════════════════════════════════════════════════════════
    
    @Value("${app.cache.data.max-size:10000}")
    private int dataMaxSize;
    
    @Value("${app.cache.data.ttl-minutes:5}")
    private int dataTtlMinutes;

    // ═══════════════════════════════════════════════════════════════
    // EVENT DEDUPLICATION CACHE SETTINGS
    // ═══════════════════════════════════════════════════════════════
    
    @Value("${app.cache.dedup.max-size:50000}")
    private int dedupMaxSize;
    
    @Value("${app.cache.dedup.ttl-minutes:60}")
    private int dedupTtlMinutes;

    /**
     * Cache for customer data indexed by order ID.
     */
    @Bean
    public Cache<String, CustomerData> customerDataCache() {
        log.info("Creating customer data cache: maxSize={}, ttl={}m", dataMaxSize, dataTtlMinutes);
        return Caffeine.newBuilder()
                .maximumSize(dataMaxSize)
                .expireAfterWrite(Duration.ofMinutes(dataTtlMinutes))
                .recordStats()
                .build();
    }

    /**
     * Cache for inventory data indexed by order ID.
     */
    @Bean
    public Cache<String, InventoryData> inventoryDataCache() {
        log.info("Creating inventory data cache: maxSize={}, ttl={}m", dataMaxSize, dataTtlMinutes);
        return Caffeine.newBuilder()
                .maximumSize(dataMaxSize)
                .expireAfterWrite(Duration.ofMinutes(dataTtlMinutes))
                .recordStats()
                .build();
    }

    /**
     * Cache for pricing data indexed by order ID.
     */
    @Bean
    public Cache<String, PricingData> pricingDataCache() {
        log.info("Creating pricing data cache: maxSize={}, ttl={}m", dataMaxSize, dataTtlMinutes);
        return Caffeine.newBuilder()
                .maximumSize(dataMaxSize)
                .expireAfterWrite(Duration.ofMinutes(dataTtlMinutes))
                .recordStats()
                .build();
    }

    /**
     * Cache for event deduplication.
     * Value is the timestamp when the event was processed (for debugging).
     */
    @Bean
    public Cache<String, Long> eventDeduplicationCache() {
        log.info("Creating event deduplication cache: maxSize={}, ttl={}m", dedupMaxSize, dedupTtlMinutes);
        return Caffeine.newBuilder()
                .maximumSize(dedupMaxSize)
                .expireAfterWrite(Duration.ofMinutes(dedupTtlMinutes))
                .recordStats()
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // PARTNER STATUS CACHE SETTINGS
    // Caches trading partner and business unit status lookups
    // ═══════════════════════════════════════════════════════════════
    
    @Value("${app.cache.partner.max-size:1000}")
    private int partnerMaxSize;
    
    @Value("${app.cache.partner.ttl-minutes:10}")
    private int partnerTtlMinutes;

    /**
     * Cache for trading partner status.
     * Key: partner name, Value: TradingPartnerStatus
     * TTL: 10 minutes (status changes are infrequent)
     */
    @Bean
    public Cache<String, TradingPartnerStatus> tradingPartnerCache() {
        log.info("Creating trading partner cache: maxSize={}, ttl={}m", partnerMaxSize, partnerTtlMinutes);
        return Caffeine.newBuilder()
                .maximumSize(partnerMaxSize)
                .expireAfterWrite(Duration.ofMinutes(partnerTtlMinutes))
                .recordStats()
                .build();
    }

    /**
     * Cache for business unit status.
     * Key: unit name, Value: BusinessUnitStatus
     * TTL: 10 minutes (status changes are infrequent)
     */
    @Bean
    public Cache<String, BusinessUnitStatus> businessUnitCache() {
        log.info("Creating business unit cache: maxSize={}, ttl={}m", partnerMaxSize, partnerTtlMinutes);
        return Caffeine.newBuilder()
                .maximumSize(partnerMaxSize)
                .expireAfterWrite(Duration.ofMinutes(partnerTtlMinutes))
                .recordStats()
                .build();
    }
}

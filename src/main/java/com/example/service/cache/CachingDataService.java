package com.example.service.cache;

import com.example.config.AppMetrics;
import com.example.model.CustomerData;
import com.example.model.InventoryData;
import com.example.model.PricingData;
import com.example.repository.OrderRepository;
import com.example.service.preload.ProcessingContext;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Caching wrapper for data preloading.
 * 
 * Sits in front of the database and:
 * 1. Checks cache for each order ID first
 * 2. Only queries DB for cache misses
 * 3. Populates cache with DB results
 * 
 * This reduces DB load for frequently accessed orders.
 * 
 * Strategy: "Cache-aside" pattern
 * - Read: Check cache → if miss, load from DB → populate cache
 * - No write-through (data changes come from external systems)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CachingDataService {

    private final OrderRepository orderRepository;
    private final AppMetrics metrics;
    
    // Individual caches for each data type
    private final Cache<String, CustomerData> customerDataCache;
    private final Cache<String, InventoryData> inventoryDataCache;
    private final Cache<String, PricingData> pricingDataCache;

    /**
     * Preload data with caching.
     * First checks cache, then fetches missing data from DB.
     * 
     * @param orderIds List of order IDs to load data for
     * @param executor Executor for parallel queries
     * @return ProcessingContext with all data (from cache + DB)
     */
    public ProcessingContext preloadData(List<String> orderIds, ExecutorService executor) {
        if (orderIds == null || orderIds.isEmpty()) {
            return emptyContext();
        }

        long startTime = System.currentTimeMillis();
        
        // Separate cached and uncached order IDs
        CacheCheckResult customerCheck = checkCache(orderIds, customerDataCache);
        CacheCheckResult inventoryCheck = checkCache(orderIds, inventoryDataCache);
        CacheCheckResult pricingCheck = checkCache(orderIds, pricingDataCache);

        logCacheStatus(orderIds.size(), customerCheck, inventoryCheck, pricingCheck);

        // Fetch missing data from DB in parallel
        CompletableFuture<Map<String, CustomerData>> customerFuture =
                fetchMissing(customerCheck.missingIds(), orderRepository::batchFetchCustomerData, 
                        customerDataCache, executor);

        CompletableFuture<Map<String, InventoryData>> inventoryFuture =
                fetchMissing(inventoryCheck.missingIds(), orderRepository::batchFetchInventoryData, 
                        inventoryDataCache, executor);

        CompletableFuture<Map<String, PricingData>> pricingFuture =
                fetchMissing(pricingCheck.missingIds(), orderRepository::batchFetchPricingData, 
                        pricingDataCache, executor);

        // Wait for all DB fetches
        CompletableFuture.allOf(customerFuture, inventoryFuture, pricingFuture).join();

        // Merge cached + fetched data
        Map<String, CustomerData> allCustomers = merge(customerCheck.cachedData(), customerFuture.join());
        Map<String, InventoryData> allInventory = merge(inventoryCheck.cachedData(), inventoryFuture.join());
        Map<String, PricingData> allPricing = merge(pricingCheck.cachedData(), pricingFuture.join());

        long elapsedTime = System.currentTimeMillis() - startTime;
        metrics.getDbFetchTimer().record(elapsedTime, TimeUnit.MILLISECONDS);
        
        log.info("Data preload completed in {}ms | Cache hits: customer={}, inventory={}, pricing={}", 
                elapsedTime, customerCheck.cachedData().size(), 
                inventoryCheck.cachedData().size(), pricingCheck.cachedData().size());

        return ProcessingContext.builder()
                .customerData(allCustomers)
                .inventoryData(allInventory)
                .pricingData(allPricing)
                .build();
    }

    /**
     * Check cache for a list of order IDs.
     * Returns which IDs were found (cached) vs missing.
     */
    private <T> CacheCheckResult<T> checkCache(List<String> orderIds, Cache<String, T> cache) {
        Map<String, T> cached = new HashMap<>();
        List<String> missing = new ArrayList<>();

        for (String orderId : orderIds) {
            T value = cache.getIfPresent(orderId);
            if (value != null) {
                cached.put(orderId, value);
            } else {
                missing.add(orderId);
            }
        }

        return new CacheCheckResult<>(cached, missing);
    }

    /**
     * Fetch missing data from DB and populate cache.
     */
    private <T> CompletableFuture<Map<String, T>> fetchMissing(
            List<String> missingIds,
            java.util.function.Function<List<String>, Map<String, T>> dbFetcher,
            Cache<String, T> cache,
            ExecutorService executor) {
        
        if (missingIds.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return CompletableFuture.supplyAsync(() -> {
            log.debug("Fetching {} missing items from DB", missingIds.size());
            Map<String, T> fetched = dbFetcher.apply(missingIds);
            
            // Populate cache with fetched data
            fetched.forEach(cache::put);
            
            return fetched;
        }, executor);
    }

    /**
     * Merge two maps.
     */
    private <T> Map<String, T> merge(Map<String, T> cached, Map<String, T> fetched) {
        Map<String, T> result = new HashMap<>(cached);
        result.putAll(fetched);
        return result;
    }

    /**
     * Log cache status.
     */
    private void logCacheStatus(int totalOrders, 
                                 CacheCheckResult<?> customer, 
                                 CacheCheckResult<?> inventory, 
                                 CacheCheckResult<?> pricing) {
        int customerHits = customer.cachedData().size();
        int inventoryHits = inventory.cachedData().size();
        int pricingHits = pricing.cachedData().size();
        
        if (customerHits > 0 || inventoryHits > 0 || pricingHits > 0) {
            log.info("Cache hits for {} orders: customer={}, inventory={}, pricing={}", 
                    totalOrders, customerHits, inventoryHits, pricingHits);
        }
    }

    /**
     * Get cache statistics for monitoring.
     */
    public CacheStatsSummary getStats() {
        return new CacheStatsSummary(
                getCacheStats("customer", customerDataCache),
                getCacheStats("inventory", inventoryDataCache),
                getCacheStats("pricing", pricingDataCache)
        );
    }

    private SingleCacheStats getCacheStats(String name, Cache<?, ?> cache) {
        var stats = cache.stats();
        return new SingleCacheStats(
                name,
                cache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate()
        );
    }

    private ProcessingContext emptyContext() {
        return ProcessingContext.builder()
                .customerData(Map.of())
                .inventoryData(Map.of())
                .pricingData(Map.of())
                .build();
    }

    /**
     * Result of checking cache for a list of IDs.
     */
    private record CacheCheckResult<T>(
            Map<String, T> cachedData,
            List<String> missingIds
    ) {}

    /**
     * Statistics for a single cache.
     */
    public record SingleCacheStats(
            String name,
            long size,
            long hits,
            long misses,
            double hitRate
    ) {}

    /**
     * Summary of all cache statistics.
     */
    public record CacheStatsSummary(
            SingleCacheStats customer,
            SingleCacheStats inventory,
            SingleCacheStats pricing
    ) {}
}

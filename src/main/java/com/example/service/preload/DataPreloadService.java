package com.example.service.preload;

import com.example.config.AppMetrics;
import com.example.model.CustomerData;
import com.example.model.InventoryData;
import com.example.model.PricingData;
import com.example.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Service responsible for batch preloading data from database.
 * Executes 3 parallel batch queries instead of N×3 sequential queries.
 * 
 * Supports chunking for large batches to avoid DB IN clause limitations
 * and improve query plan optimization.
 * 
 * This is the key optimization - reducing DB round trips from O(N) to O(1).
 */
@Service
@Slf4j
public class DataPreloadService {

    private final OrderRepository orderRepository;
    private final AppMetrics metrics;
    private final int chunkSize;

    /**
     * Primary constructor for Spring DI.
     * 
     * @param orderRepository Repository for DB operations
     * @param metrics Application metrics
     * @param chunkSize Maximum IDs per SQL IN clause (default 500)
     *                  - SQL Server limit: ~2100 parameters
     *                  - Oracle hard limit: 1000
     *                  - Smaller chunks = better query plans
     */
    public DataPreloadService(
            OrderRepository orderRepository, 
            AppMetrics metrics,
            @Value("${app.db.chunk-size:500}") int chunkSize) {
        this.orderRepository = orderRepository;
        this.metrics = metrics;
        this.chunkSize = chunkSize;
        log.info("DataPreloadService initialized with chunkSize={}", chunkSize);
    }

    /**
     * Preload all required data for a batch of orders.
     * Executes 3 queries in parallel using virtual threads.
     * For large batches, automatically chunks queries to avoid DB limitations.
     * 
     * @param orderIds List of order IDs to preload data for
     * @param executor Executor to use for parallel execution
     * @return ProcessingContext containing all preloaded data
     */
    public ProcessingContext preloadData(List<String> orderIds, ExecutorService executor) {
        if (orderIds == null || orderIds.isEmpty()) {
            return ProcessingContext.builder()
                    .customerData(Map.of())
                    .inventoryData(Map.of())
                    .pricingData(Map.of())
                    .build();
        }

        int orderCount = orderIds.size();
        int chunks = (orderCount + chunkSize - 1) / chunkSize;
        
        log.info("Preloading data for {} orders with 3 PARALLEL BATCH queries (chunkSize={}, chunks={})...", 
                orderCount, chunkSize, chunks);
        long startTime = System.currentTimeMillis();

        // Execute all 3 data types in parallel, with chunking for large batches
        CompletableFuture<Map<String, CustomerData>> customerFuture =
                CompletableFuture.supplyAsync(
                        () -> loadDataInChunks(orderIds, this::loadCustomerData, executor, "customer"), 
                        executor);

        CompletableFuture<Map<String, InventoryData>> inventoryFuture =
                CompletableFuture.supplyAsync(
                        () -> loadDataInChunks(orderIds, this::loadInventoryData, executor, "inventory"), 
                        executor);

        CompletableFuture<Map<String, PricingData>> pricingFuture =
                CompletableFuture.supplyAsync(
                        () -> loadDataInChunks(orderIds, this::loadPricingData, executor, "pricing"), 
                        executor);

        // Wait for all queries to complete
        CompletableFuture.allOf(customerFuture, inventoryFuture, pricingFuture).join();

        long elapsedTime = System.currentTimeMillis() - startTime;
        metrics.getDbFetchTimer().record(elapsedTime, TimeUnit.MILLISECONDS);
        log.info("Data preload completed in {}ms (3 parallel batch queries, {} chunks each)", 
                elapsedTime, chunks);

        return ProcessingContext.builder()
                .customerData(customerFuture.join())
                .inventoryData(inventoryFuture.join())
                .pricingData(pricingFuture.join())
                .build();
    }

    /**
     * Load data in chunks for large batches.
     * Chunks are processed in parallel for maximum throughput.
     * 
     * @param orderIds All order IDs to process
     * @param loader Function to load data for a chunk
     * @param executor Executor for parallel chunk processing
     * @param dataType Name for logging
     * @return Combined map of all results
     */
    private <T> Map<String, T> loadDataInChunks(
            List<String> orderIds, 
            Function<List<String>, Map<String, T>> loader,
            ExecutorService executor,
            String dataType) {
        
        // If small enough, process in single query
        if (orderIds.size() <= chunkSize) {
            return loader.apply(orderIds);
        }

        // Split into chunks and process in parallel
        List<List<String>> chunks = partition(orderIds, chunkSize);
        log.debug("Processing {} data in {} chunks of up to {} IDs each", 
                dataType, chunks.size(), chunkSize);

        // Process all chunks in parallel
        List<CompletableFuture<Map<String, T>>> chunkFutures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> loader.apply(chunk), executor))
                .toList();

        // Wait for all chunks and merge results
        CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0])).join();

        Map<String, T> result = new HashMap<>();
        for (CompletableFuture<Map<String, T>> future : chunkFutures) {
            result.putAll(future.join());
        }
        
        return result;
    }

    /**
     * Partition a list into chunks of specified size.
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    /**
     * Load customer data with retry logic.
     */
    private Map<String, CustomerData> loadCustomerData(List<String> orderIds) {
        log.debug("Loading customer data for {} orders", orderIds.size());
        return orderRepository.batchFetchCustomerData(orderIds);
    }

    /**
     * Load inventory data with retry logic.
     */
    private Map<String, InventoryData> loadInventoryData(List<String> orderIds) {
        log.debug("Loading inventory data for {} orders", orderIds.size());
        return orderRepository.batchFetchInventoryData(orderIds);
    }

    /**
     * Load pricing data with retry logic.
     */
    private Map<String, PricingData> loadPricingData(List<String> orderIds) {
        log.debug("Loading pricing data for {} orders", orderIds.size());
        return orderRepository.batchFetchPricingData(orderIds);
    }
}

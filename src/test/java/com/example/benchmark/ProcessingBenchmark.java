package com.example.benchmark;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * Benchmark comparing three processing strategies:
 * 1. SEQUENTIAL - DB calls in a loop (one at a time)
 * 2. PREFETCH - Batch load all data upfront, then process
 * 3. PARALLEL - Process items concurrently with parallel DB calls
 * 
 * Run with: mvn test -Dtest=ProcessingBenchmark
 */
public class ProcessingBenchmark {

    // Simulated DB latency (milliseconds)
    private static final int DB_LATENCY_MS = 50;
    
    // Number of orders to process
    private static final int ORDER_COUNT = 100;
    
    // Thread pool for parallel processing
    private static final int THREAD_POOL_SIZE = 20;

    public static void main(String[] args) throws Exception {
        ProcessingBenchmark benchmark = new ProcessingBenchmark();
        
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         Processing Strategy Benchmark Comparison             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("Configuration: %d orders, %dms DB latency, %d threads%n", 
                ORDER_COUNT, DB_LATENCY_MS, THREAD_POOL_SIZE);
        System.out.println("Each order requires 3 DB calls: Customer, Inventory, Pricing");
        System.out.println();
        
        // Warmup
        System.out.println("Warming up JVM...");
        benchmark.runSequential(10);
        benchmark.runPrefetch(10);
        benchmark.runParallel(10);
        System.out.println();
        
        // Run benchmarks multiple times for accuracy
        int iterations = 3;
        
        long[] sequentialTimes = new long[iterations];
        long[] prefetchTimes = new long[iterations];
        long[] parallelTimes = new long[iterations];
        
        for (int i = 0; i < iterations; i++) {
            System.out.printf("═══ Iteration %d of %d ═══%n", i + 1, iterations);
            
            // Sequential
            sequentialTimes[i] = benchmark.runSequential(ORDER_COUNT);
            System.out.printf("  Sequential:  %,d ms%n", sequentialTimes[i]);
            
            // Prefetch
            prefetchTimes[i] = benchmark.runPrefetch(ORDER_COUNT);
            System.out.printf("  Prefetch:    %,d ms%n", prefetchTimes[i]);
            
            // Parallel
            parallelTimes[i] = benchmark.runParallel(ORDER_COUNT);
            System.out.printf("  Parallel:    %,d ms%n", parallelTimes[i]);
            
            System.out.println();
        }
        
        // Calculate averages
        long avgSequential = Arrays.stream(sequentialTimes).sum() / iterations;
        long avgPrefetch = Arrays.stream(prefetchTimes).sum() / iterations;
        long avgParallel = Arrays.stream(parallelTimes).sum() / iterations;
        
        // Print results
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    BENCHMARK RESULTS                         ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Sequential (DB in loop):     %,6d ms  (baseline)          ║%n", avgSequential);
        System.out.printf("║  Prefetch (batch load):       %,6d ms  (%.1fx faster)       ║%n", 
                avgPrefetch, (double) avgSequential / avgPrefetch);
        System.out.printf("║  Parallel (concurrent):       %,6d ms  (%.1fx faster)       ║%n", 
                avgParallel, (double) avgSequential / avgParallel);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        
        // Theoretical analysis
        System.out.println();
        System.out.println("═══ Theoretical Analysis ═══");
        int totalDbCalls = ORDER_COUNT * 3;
        System.out.printf("Total DB calls: %d orders × 3 calls = %d calls%n", ORDER_COUNT, totalDbCalls);
        System.out.println();
        
        System.out.println("SEQUENTIAL: Each call blocks, total time = N × 3 × latency");
        System.out.printf("  Expected: %d × 3 × %dms = %,d ms%n", ORDER_COUNT, DB_LATENCY_MS, ORDER_COUNT * 3 * DB_LATENCY_MS);
        
        System.out.println();
        System.out.println("PREFETCH: 3 batch queries (customer, inventory, pricing)");
        System.out.printf("  Expected: 3 × %dms = %d ms (+ processing overhead)%n", DB_LATENCY_MS, 3 * DB_LATENCY_MS);
        
        System.out.println();
        System.out.println("PARALLEL: Concurrent calls limited by thread pool");
        int batches = (int) Math.ceil((double) totalDbCalls / THREAD_POOL_SIZE);
        System.out.printf("  Expected: ceil(%d / %d) × %dms = %d ms%n", 
                totalDbCalls, THREAD_POOL_SIZE, DB_LATENCY_MS, batches * DB_LATENCY_MS);
        
        System.out.println();
        System.out.println("═══ Recommendation ═══");
        if (avgPrefetch <= avgParallel) {
            System.out.println("✓ PREFETCH is optimal for this workload");
            System.out.println("  - Single batch query per data type");
            System.out.println("  - Minimal network round trips");
            System.out.println("  - Best when you know all IDs upfront");
        } else {
            System.out.println("✓ PARALLEL is optimal for this workload");
            System.out.println("  - Good when data can't be batched");
            System.out.println("  - Works with external APIs");
        }
    }

    /**
     * STRATEGY 1: Sequential - DB calls in a loop
     * Each order processed one at a time, each DB call blocks
     */
    public long runSequential(int orderCount) {
        Instant start = Instant.now();
        
        List<String> orderIds = generateOrderIds(orderCount);
        List<ProcessedOrder> results = new ArrayList<>();
        
        for (String orderId : orderIds) {
            // 3 sequential DB calls per order
            CustomerData customer = fetchCustomerSequential(orderId);
            InventoryData inventory = fetchInventorySequential(orderId);
            PricingData pricing = fetchPricingSequential(orderId);
            
            // Process the data
            results.add(new ProcessedOrder(orderId, customer, inventory, pricing));
        }
        
        return Duration.between(start, Instant.now()).toMillis();
    }

    /**
     * STRATEGY 2: Prefetch - Batch load all data, then process
     * Load all customers, inventory, pricing in 3 batch queries
     */
    public long runPrefetch(int orderCount) {
        Instant start = Instant.now();
        
        List<String> orderIds = generateOrderIds(orderCount);
        
        // Batch fetch all data upfront (3 queries total)
        Map<String, CustomerData> customers = batchFetchCustomers(orderIds);
        Map<String, InventoryData> inventory = batchFetchInventory(orderIds);
        Map<String, PricingData> pricing = batchFetchPricing(orderIds);
        
        // Process using prefetched data (no DB calls)
        List<ProcessedOrder> results = new ArrayList<>();
        for (String orderId : orderIds) {
            results.add(new ProcessedOrder(
                orderId,
                customers.get(orderId),
                inventory.get(orderId),
                pricing.get(orderId)
            ));
        }
        
        return Duration.between(start, Instant.now()).toMillis();
    }

    /**
     * STRATEGY 3: Parallel - Process orders concurrently using Virtual Threads
     * Each order's 3 DB calls happen in parallel
     */
    public long runParallel(int orderCount) throws Exception {
        Instant start = Instant.now();
        
        List<String> orderIds = generateOrderIds(orderCount);
        
        // Use Virtual Threads - no thread pool starvation!
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<ProcessedOrder>> futures = orderIds.stream()
                .map(orderId -> CompletableFuture.supplyAsync(() -> {
                    // All 3 DB calls for this order run in parallel
                    CompletableFuture<CustomerData> customerFuture = 
                        CompletableFuture.supplyAsync(() -> fetchCustomerSequential(orderId), executor);
                    CompletableFuture<InventoryData> inventoryFuture = 
                        CompletableFuture.supplyAsync(() -> fetchInventorySequential(orderId), executor);
                    CompletableFuture<PricingData> pricingFuture = 
                        CompletableFuture.supplyAsync(() -> fetchPricingSequential(orderId), executor);
                    
                    // Wait for all 3 and combine
                    return new ProcessedOrder(
                        orderId,
                        customerFuture.join(),
                        inventoryFuture.join(),
                        pricingFuture.join()
                    );
                }, executor))
                .toList();
            
            // Wait for all orders to complete
            List<ProcessedOrder> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        }
        
        return Duration.between(start, Instant.now()).toMillis();
    }

    // ═══════════════════════════════════════════════════════════════
    // Simulated DB calls
    // ═══════════════════════════════════════════════════════════════

    private CustomerData fetchCustomerSequential(String orderId) {
        simulateDbLatency();
        return new CustomerData(orderId, "Customer-" + orderId, "customer@example.com");
    }

    private InventoryData fetchInventorySequential(String orderId) {
        simulateDbLatency();
        return new InventoryData(orderId, 100, "WAREHOUSE-A");
    }

    private PricingData fetchPricingSequential(String orderId) {
        simulateDbLatency();
        return new PricingData(orderId, 99.99, 0.10);
    }

    // Batch fetch simulates a single query with IN clause
    private Map<String, CustomerData> batchFetchCustomers(List<String> orderIds) {
        simulateDbLatency(); // Single DB call for all
        Map<String, CustomerData> result = new HashMap<>();
        for (String id : orderIds) {
            result.put(id, new CustomerData(id, "Customer-" + id, "customer@example.com"));
        }
        return result;
    }

    private Map<String, InventoryData> batchFetchInventory(List<String> orderIds) {
        simulateDbLatency(); // Single DB call for all
        Map<String, InventoryData> result = new HashMap<>();
        for (String id : orderIds) {
            result.put(id, new InventoryData(id, 100, "WAREHOUSE-A"));
        }
        return result;
    }

    private Map<String, PricingData> batchFetchPricing(List<String> orderIds) {
        simulateDbLatency(); // Single DB call for all
        Map<String, PricingData> result = new HashMap<>();
        for (String id : orderIds) {
            result.put(id, new PricingData(id, 99.99, 0.10));
        }
        return result;
    }

    private void simulateDbLatency() {
        try {
            Thread.sleep(DB_LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<String> generateOrderIds(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> "ORD-" + String.format("%05d", i))
            .toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // Data classes
    // ═══════════════════════════════════════════════════════════════

    record CustomerData(String orderId, String name, String email) {}
    record InventoryData(String orderId, int quantity, String warehouse) {}
    record PricingData(String orderId, double price, double discount) {}
    record ProcessedOrder(String orderId, CustomerData customer, InventoryData inventory, PricingData pricing) {}
}

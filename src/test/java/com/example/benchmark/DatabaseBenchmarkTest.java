package com.example.benchmark;

import com.example.model.*;
import com.example.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

/**
 * Integration Benchmark Test using actual H2 Database.
 * 
 * Compares three strategies:
 * 1. SEQUENTIAL - Individual DB queries in a loop
 * 2. PREFETCH   - Batch queries (single query returns all data)
 * 3. PARALLEL   - Concurrent individual queries with Virtual Threads
 * 
 * Run with: mvn test -Dtest=DatabaseBenchmarkTest
 */
@SpringBootTest
@ActiveProfiles("test")  // Uses H2 database from application-test.yml
public class DatabaseBenchmarkTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    private static final int ORDER_COUNT = 100;
    private static final int WARMUP_COUNT = 10;
    private static final int ITERATIONS = 3;

    private List<String> orderIds;

    @BeforeEach
    void setup() {
        orderIds = IntStream.rangeClosed(1, ORDER_COUNT)
                .mapToObj(i -> String.format("ORD-%03d", i))
                .toList();
    }

    @Test
    void runBenchmarkComparison() throws Exception {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       Database Processing Strategy Benchmark (H2 DB)         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("Configuration: %d orders, %d iterations%n", ORDER_COUNT, ITERATIONS);
        System.out.println("Each order requires 3 DB lookups: Customer, Inventory, Pricing");
        System.out.println();

        // Warmup
        System.out.println("Warming up JVM and connection pool...");
        runSequential(WARMUP_COUNT);
        runPrefetch(WARMUP_COUNT);
        runParallel(WARMUP_COUNT);
        System.out.println("Warmup complete.\n");

        // Run benchmarks
        long[] sequentialTimes = new long[ITERATIONS];
        long[] prefetchTimes = new long[ITERATIONS];
        long[] parallelTimes = new long[ITERATIONS];

        for (int i = 0; i < ITERATIONS; i++) {
            System.out.printf("═══ Iteration %d of %d ═══%n", i + 1, ITERATIONS);

            sequentialTimes[i] = runSequential(ORDER_COUNT);
            System.out.printf("  Sequential:  %,d ms%n", sequentialTimes[i]);

            prefetchTimes[i] = runPrefetch(ORDER_COUNT);
            System.out.printf("  Prefetch:    %,d ms%n", prefetchTimes[i]);

            parallelTimes[i] = runParallel(ORDER_COUNT);
            System.out.printf("  Parallel:    %,d ms%n", parallelTimes[i]);

            System.out.println();
        }

        // Calculate averages
        long avgSequential = average(sequentialTimes);
        long avgPrefetch = average(prefetchTimes);
        long avgParallel = average(parallelTimes);

        // Print results
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    BENCHMARK RESULTS                         ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Sequential (N queries/table):  %,6d ms  (baseline)          ║%n", avgSequential);
        System.out.printf("║  Prefetch (3 batch queries):    %,6d ms  (%.1fx faster)      ║%n", 
                avgPrefetch, (double) avgSequential / avgPrefetch);
        System.out.printf("║  Parallel (virtual threads):    %,6d ms  (%.1fx faster)      ║%n", 
                avgParallel, (double) avgSequential / avgParallel);
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Analysis
        System.out.println("═══ Analysis ═══");
        System.out.println("Total DB queries per strategy:");
        System.out.printf("  SEQUENTIAL: %d orders × 3 queries = %d individual queries%n", 
                ORDER_COUNT, ORDER_COUNT * 3);
        System.out.println("  PREFETCH:   3 batch queries (using IN clause)");
        System.out.printf("  PARALLEL:   %d queries executed concurrently%n", ORDER_COUNT * 3);
        System.out.println();

        // Recommendation
        System.out.println("═══ Recommendation ═══");
        if (avgPrefetch <= avgParallel) {
            System.out.println("✓ PREFETCH is optimal - batch queries reduce total DB calls");
            System.out.println("  Best when: All data is in your database, can batch by IDs");
        } else {
            System.out.println("✓ PARALLEL is optimal - concurrent execution maximizes throughput");
            System.out.println("  Best when: External APIs, can't batch, need lowest latency");
        }
        System.out.println("✗ SEQUENTIAL should be avoided - serialized calls cause high latency");
        System.out.println();
    }

    /**
     * STRATEGY 1: Sequential - One query at a time per order
     * This is the ANTI-PATTERN we want to avoid!
     */
    private long runSequential(int count) {
        Instant start = Instant.now();
        List<String> testIds = orderIds.subList(0, Math.min(count, orderIds.size()));

        List<EnrichedOrder> results = new ArrayList<>();
        for (String orderId : testIds) {
            // 3 individual DB queries per order - SLOW!
            CustomerData customer = fetchCustomerSingle(orderId);
            InventoryData inventory = fetchInventorySingle(orderId);
            PricingData pricing = fetchPricingSingle(orderId);
            results.add(new EnrichedOrder(orderId, customer, inventory, pricing));
        }

        return Duration.between(start, Instant.now()).toMillis();
    }

    /**
     * STRATEGY 2: Prefetch - Batch load all data upfront
     * Uses IN clause to fetch all data in 3 queries total
     */
    private long runPrefetch(int count) {
        Instant start = Instant.now();
        List<String> testIds = orderIds.subList(0, Math.min(count, orderIds.size()));

        // 3 batch queries instead of N*3 individual queries
        Map<String, CustomerData> customers = batchFetchCustomers(testIds);
        Map<String, InventoryData> inventory = batchFetchInventory(testIds);
        Map<String, PricingData> pricing = batchFetchPricing(testIds);

        // Process using prefetched data (no DB calls)
        List<EnrichedOrder> results = testIds.stream()
                .map(id -> new EnrichedOrder(id, customers.get(id), inventory.get(id), pricing.get(id)))
                .toList();

        return Duration.between(start, Instant.now()).toMillis();
    }

    /**
     * STRATEGY 3: Parallel - Execute individual queries concurrently
     * Uses Virtual Threads for maximum concurrency
     */
    private long runParallel(int count) throws Exception {
        Instant start = Instant.now();
        List<String> testIds = orderIds.subList(0, Math.min(count, orderIds.size()));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<EnrichedOrder>> futures = testIds.stream()
                    .map(orderId -> CompletableFuture.supplyAsync(() -> {
                        // Execute 3 queries per order in parallel
                        CompletableFuture<CustomerData> customerF = 
                                CompletableFuture.supplyAsync(() -> fetchCustomerSingle(orderId), executor);
                        CompletableFuture<InventoryData> inventoryF = 
                                CompletableFuture.supplyAsync(() -> fetchInventorySingle(orderId), executor);
                        CompletableFuture<PricingData> pricingF = 
                                CompletableFuture.supplyAsync(() -> fetchPricingSingle(orderId), executor);

                        return new EnrichedOrder(
                                orderId,
                                customerF.join(),
                                inventoryF.join(),
                                pricingF.join()
                        );
                    }, executor))
                    .toList();

            List<EnrichedOrder> results = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        }

        return Duration.between(start, Instant.now()).toMillis();
    }

    // ═══════════════════════════════════════════════════════════════
    // Single-row DB queries (for Sequential and Parallel strategies)
    // ═══════════════════════════════════════════════════════════════

    private CustomerData fetchCustomerSingle(String orderId) {
        String sql = """
            SELECT c.customer_id, c.name, c.email, c.tier
            FROM customers c
            INNER JOIN orders o ON c.customer_id = o.customer_id
            WHERE o.order_id = :orderId
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orderId", orderId);
        
        return jdbcTemplate.query(sql, params, rs -> {
            if (rs.next()) {
                return new CustomerData(
                        rs.getString("customer_id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("tier")
                );
            }
            return null;
        });
    }

    private InventoryData fetchInventorySingle(String orderId) {
        String sql = """
            SELECT oi.order_id, i.sku, i.quantity_available, i.warehouse_location
            FROM inventory i
            INNER JOIN order_items oi ON i.sku = oi.sku
            WHERE oi.order_id = :orderId
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orderId", orderId);

        return jdbcTemplate.query(sql, params, rs -> {
            if (rs.next()) {
                return new InventoryData(
                        rs.getString("order_id"),
                        rs.getString("sku"),
                        rs.getInt("quantity_available"),
                        rs.getString("warehouse_location")
                );
            }
            return null;
        });
    }

    private PricingData fetchPricingSingle(String orderId) {
        String sql = """
            SELECT order_id, base_price, discount, tax_rate
            FROM order_pricing
            WHERE order_id = :orderId
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orderId", orderId);

        return jdbcTemplate.query(sql, params, rs -> {
            if (rs.next()) {
                return new PricingData(
                        rs.getString("order_id"),
                        rs.getBigDecimal("base_price"),
                        rs.getBigDecimal("discount"),
                        rs.getBigDecimal("tax_rate")
                );
            }
            return null;
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // Batch DB queries (for Prefetch strategy)
    // ═══════════════════════════════════════════════════════════════

    private Map<String, CustomerData> batchFetchCustomers(List<String> orderIds) {
        String sql = """
            SELECT o.order_id, c.customer_id, c.name, c.email, c.tier
            FROM customers c
            INNER JOIN orders o ON c.customer_id = o.customer_id
            WHERE o.order_id IN (:orderIds)
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orderIds", orderIds);

        return jdbcTemplate.query(sql, params, rs -> {
            Map<String, CustomerData> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("order_id"), new CustomerData(
                        rs.getString("customer_id"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("tier")
                ));
            }
            return result;
        });
    }

    private Map<String, InventoryData> batchFetchInventory(List<String> orderIds) {
        String sql = """
            SELECT oi.order_id, i.sku, i.quantity_available, i.warehouse_location
            FROM inventory i
            INNER JOIN order_items oi ON i.sku = oi.sku
            WHERE oi.order_id IN (:orderIds)
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orderIds", orderIds);

        return jdbcTemplate.query(sql, params, rs -> {
            Map<String, InventoryData> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("order_id"), new InventoryData(
                        rs.getString("order_id"),
                        rs.getString("sku"),
                        rs.getInt("quantity_available"),
                        rs.getString("warehouse_location")
                ));
            }
            return result;
        });
    }

    private Map<String, PricingData> batchFetchPricing(List<String> orderIds) {
        String sql = """
            SELECT order_id, base_price, discount, tax_rate
            FROM order_pricing
            WHERE order_id IN (:orderIds)
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orderIds", orderIds);

        return jdbcTemplate.query(sql, params, rs -> {
            Map<String, PricingData> result = new HashMap<>();
            while (rs.next()) {
                result.put(rs.getString("order_id"), new PricingData(
                        rs.getString("order_id"),
                        rs.getBigDecimal("base_price"),
                        rs.getBigDecimal("discount"),
                        rs.getBigDecimal("tax_rate")
                ));
            }
            return result;
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper classes and methods
    // ═══════════════════════════════════════════════════════════════

    private record EnrichedOrder(
            String orderId,
            CustomerData customer,
            InventoryData inventory,
            PricingData pricing
    ) {}

    private long average(long[] values) {
        return (long) Arrays.stream(values).average().orElse(0);
    }
}

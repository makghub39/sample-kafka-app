package com.example.service;

import com.example.config.AppMetrics;
import com.example.model.*;
import com.example.repository.OrderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Order processor that combines:
 * 1. Batch DB calls (3 queries instead of N×3)
 * 2. Parallel DB calls (all 3 run simultaneously)
 * 3. Parallel order processing (with concurrency limit)
 * 4. Parallel WMQ publishing (with concurrency limit)
 * 
 * Uses virtual threads with Semaphore to limit concurrent operations.
 */
@Service
@Slf4j
public class OrderProcessor {

    private final OrderRepository orderRepository;
    private final WmqPublisher wmqPublisher;
    private final AppMetrics metrics;
    
    // Concurrency limits to prevent overwhelming downstream systems
    private final Semaphore processingSemaphore;

    public OrderProcessor(
            OrderRepository orderRepository, 
            WmqPublisher wmqPublisher, 
            AppMetrics metrics,
            @Value("${app.executor.processing-concurrency:100}") int processingConcurrency) {
        this.orderRepository = orderRepository;
        this.wmqPublisher = wmqPublisher;
        this.metrics = metrics;
        this.processingSemaphore = new Semaphore(processingConcurrency);
        log.info("OrderProcessor initialized with processing concurrency limit: {}", processingConcurrency);
    }

    /**
     * Main processing method with default (no grouping) for backward compatibility.
     */
    public ProcessingResult processOrders(List<Order> orders) {
        return processOrders(orders, false);
    }

    /**
     * Main processing method - optimized with batch DB calls, parallel processing, and parallel WMQ publish.
     * Uses ONE virtual thread executor for all async operations.
     * 
     * @param orders List of orders to process
     * @param useGrouping If true, applies grouping logic before WMQ publish; if false, sends individual messages
     */
    public ProcessingResult processOrders(List<Order> orders, boolean useGrouping) {
        if (orders.isEmpty()) {
            return new ProcessingResult(List.of(), List.of(), 0);
        }

        long startTime = System.currentTimeMillis();
        List<String> orderIds = orders.stream().map(Order::id).toList();

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("Processing {} orders | Grouping: {}", orders.size(), useGrouping ? "ENABLED" : "DISABLED");
        log.info("═══════════════════════════════════════════════════════════════");

        // ONE executor for everything - virtual threads are lightweight
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            
            // ═══════════════════════════════════════════════════════════════
            // STEP 1: Parallel batch DB calls (3 virtual threads)
            // ═══════════════════════════════════════════════════════════════
            log.info("STEP 1: Fetching data with 3 PARALLEL BATCH queries...");
            long dbStartTime = System.currentTimeMillis();

            CompletableFuture<Map<String, CustomerData>> customerFuture =
                    CompletableFuture.supplyAsync(
                            () -> orderRepository.batchFetchCustomerData(orderIds), executor);

            CompletableFuture<Map<String, InventoryData>> inventoryFuture =
                    CompletableFuture.supplyAsync(
                            () -> orderRepository.batchFetchInventoryData(orderIds), executor);

            CompletableFuture<Map<String, PricingData>> pricingFuture =
                    CompletableFuture.supplyAsync(
                            () -> orderRepository.batchFetchPricingData(orderIds), executor);

            // Wait for all DB calls
            CompletableFuture.allOf(customerFuture, inventoryFuture, pricingFuture).join();

            Map<String, CustomerData> customerMap = customerFuture.join();
            Map<String, InventoryData> inventoryMap = inventoryFuture.join();
            Map<String, PricingData> pricingMap = pricingFuture.join();

            long dbTime = System.currentTimeMillis() - dbStartTime;
            metrics.getDbFetchTimer().record(dbTime, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.info("DB fetch completed in {}ms (3 parallel batch queries)", dbTime);

            // ═══════════════════════════════════════════════════════════════
            // STEP 2: Parallel order processing (with concurrency limit)
            // ═══════════════════════════════════════════════════════════════
            log.info("STEP 2: Processing {} orders in PARALLEL (max {} concurrent)...", 
                    orders.size(), processingSemaphore.availablePermits());
            long processingStartTime = System.currentTimeMillis();

            List<ProcessedOrder> successes = new CopyOnWriteArrayList<>();
            List<FailedOrder> failures = new CopyOnWriteArrayList<>();

            List<CompletableFuture<Void>> processingFutures = orders.stream()
                    .map(order -> CompletableFuture.runAsync(() -> {
                        try {
                            // Acquire permit before processing (blocks if limit reached)
                            processingSemaphore.acquire();
                            try {
                                ProcessedOrder result = processOrderLogic(
                                        order, customerMap, inventoryMap, pricingMap);
                                successes.add(result);
                            } finally {
                                processingSemaphore.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Processing interrupted for order {}", order.id());
                            failures.add(new FailedOrder(order, "Processing interrupted", "InterruptedException"));
                        } catch (Exception e) {
                            log.warn("Failed to process order {}: {}", order.id(), e.getMessage());
                            failures.add(new FailedOrder(order, e.getMessage(), e.getClass().getSimpleName()));
                        }
                    }, executor))
                    .toList();

            // Wait for all processing to complete
            CompletableFuture.allOf(processingFutures.toArray(new CompletableFuture[0])).join();

            long processingTime = System.currentTimeMillis() - processingStartTime;
            metrics.getProcessingTimer().record(processingTime, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.info("Processing completed in {}ms", processingTime);

            // ═══════════════════════════════════════════════════════════════
            // STEP 3: Parallel WMQ publishing (conditional grouping)
            // ═══════════════════════════════════════════════════════════════
            log.info("STEP 3: Publishing {} processed orders to WMQ (grouping: {})...", 
                    successes.size(), useGrouping);
            long wmqStartTime = System.currentTimeMillis();

            // Conditional: grouped vs individual publishing
            if (useGrouping) {
                wmqPublisher.sendBatch(successes, executor);  // Applies grouping logic
            } else {
                wmqPublisher.sendBatchWithoutGrouping(successes, executor);  // Individual messages only
            }

            long wmqTime = System.currentTimeMillis() - wmqStartTime;
            metrics.getWmqPublishTimer().record(wmqTime, java.util.concurrent.TimeUnit.MILLISECONDS);
            log.info("WMQ publish completed in {}ms", wmqTime);

            // ═══════════════════════════════════════════════════════════════
            // SUMMARY
            // ═══════════════════════════════════════════════════════════════
            long totalTime = System.currentTimeMillis() - startTime;
            metrics.getTotalProcessingTimer().record(totalTime, java.util.concurrent.TimeUnit.MILLISECONDS);
            metrics.incrementOrdersProcessed(successes.size());
            metrics.incrementOrdersFailed(failures.size());
            
            log.info("═══════════════════════════════════════════════════════════════");
            log.info("TOTAL: {}ms | DB: {}ms | Process: {}ms | WMQ: {}ms", 
                    totalTime, dbTime, processingTime, wmqTime);
            log.info("Successes: {} | Failures: {}", successes.size(), failures.size());
            log.info("═══════════════════════════════════════════════════════════════");

            return new ProcessingResult(
                    List.copyOf(successes),
                    List.copyOf(failures),
                    totalTime
            );
        }
    }

    /**
     * Pure business logic - no DB calls, just in-memory processing.
     * This is where your actual business logic goes.
     */
    private ProcessedOrder processOrderLogic(
            Order order,
            Map<String, CustomerData> customerMap,
            Map<String, InventoryData> inventoryMap,
            Map<String, PricingData> pricingMap) {

        // PRODUCTION: No artificial delay - real processing only
        // simulateProcessingTime();

        CustomerData customer = customerMap.get(order.id());
        InventoryData inventory = inventoryMap.get(order.id());
        PricingData pricing = pricingMap.get(order.id());

        // Business logic calculations
        BigDecimal finalPrice = calculateFinalPrice(pricing, customer);
        String status = determineStatus(inventory);

        return new ProcessedOrder(
                order.id(),
                order.customerId(),
                customer != null ? customer.name() : "Unknown",
                customer != null ? customer.tier() : "STANDARD",
                finalPrice,
                inventory != null ? inventory.warehouseLocation() : "DEFAULT",
                status,
                LocalDateTime.now(),
                Thread.currentThread().getName() // Shows virtual thread name
        );
    }

    private BigDecimal calculateFinalPrice(PricingData pricing, CustomerData customer) {
        if (pricing == null) return BigDecimal.ZERO;

        BigDecimal base = pricing.basePrice();
        BigDecimal discount = pricing.discount();
        BigDecimal taxRate = pricing.taxRate();

        // Apply tier discount
        if (customer != null && "PREMIUM".equals(customer.tier())) {
            discount = discount.add(new BigDecimal("0.05")); // 5% extra for premium
        } else if (customer != null && "GOLD".equals(customer.tier())) {
            discount = discount.add(new BigDecimal("0.10")); // 10% extra for gold
        }

        // Calculate: (base - discount) * (1 + tax)
        BigDecimal discountedPrice = base.subtract(base.multiply(discount));
        BigDecimal finalPrice = discountedPrice.multiply(BigDecimal.ONE.add(taxRate));

        return finalPrice.setScale(2, RoundingMode.HALF_UP);
    }

    private String determineStatus(InventoryData inventory) {
        if (inventory == null) return "PENDING_INVENTORY";
        if (inventory.quantityAvailable() > 10) return "READY_TO_SHIP";
        if (inventory.quantityAvailable() > 0) return "LOW_STOCK";
        return "BACKORDER";
    }

    private void simulateProcessingTime() {
        try {
            Thread.sleep(10 + (long) (Math.random() * 20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

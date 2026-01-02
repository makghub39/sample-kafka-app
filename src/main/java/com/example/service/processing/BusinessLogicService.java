package com.example.service.processing;

import com.example.model.Order;
import com.example.model.CustomerData;
import com.example.model.InventoryData;
import com.example.model.PricingData;
import com.example.model.ProcessedOrder;
import com.example.model.FailedOrder;
import com.example.service.preload.ProcessingContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Service responsible for the core business logic of order processing.
 * Takes preloaded data and applies business rules to each order.
 * 
 * Uses Semaphore to limit concurrent processing and prevent resource exhaustion.
 */
@Service
@Slf4j
public class BusinessLogicService {

    private final Semaphore processingSemaphore;

    public BusinessLogicService(
            @Value("${app.executor.processing-concurrency:100}") int processingConcurrency) {
        this.processingSemaphore = new Semaphore(processingConcurrency);
        log.info("BusinessLogicService initialized with concurrency limit: {}", processingConcurrency);
    }

    /**
     * Process all orders in parallel with concurrency limit.
     * 
     * @param orders List of orders to process
     * @param context Preloaded data context
     * @param executor Executor for parallel processing
     * @return Tuple of successes and failures
     */
    public ProcessingOutput processOrders(List<Order> orders, ProcessingContext context, ExecutorService executor) {
        if (orders.isEmpty()) {
            return new ProcessingOutput(List.of(), List.of());
        }

        log.info("Processing {} orders in PARALLEL (max {} concurrent)...", 
                orders.size(), processingSemaphore.availablePermits());

        List<ProcessedOrder> successes = new CopyOnWriteArrayList<>();
        List<FailedOrder> failures = new CopyOnWriteArrayList<>();

        List<CompletableFuture<Void>> futures = orders.stream()
                .map(order -> CompletableFuture.runAsync(() -> 
                        processWithSemaphore(order, context, successes, failures), executor))
                .toList();

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("Processing complete: {} successes, {} failures", successes.size(), failures.size());
        return new ProcessingOutput(List.copyOf(successes), List.copyOf(failures));
    }

    /**
     * Process a single order with semaphore control.
     */
    private void processWithSemaphore(Order order, ProcessingContext context,
                                       List<ProcessedOrder> successes, List<FailedOrder> failures) {
        try {
            processingSemaphore.acquire();
            try {
                ProcessedOrder result = processOrder(order, context);
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
    }

    /**
     * Pure business logic - processes a single order using preloaded data.
     */
    private ProcessedOrder processOrder(Order order, ProcessingContext context) {
        CustomerData customer = context.getCustomer(order.id());
        InventoryData inventory = context.getInventory(order.id());
        PricingData pricing = context.getPricing(order.id());

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
                Thread.currentThread().getName()
        );
    }

    /**
     * Calculate final price with tier discounts and tax.
     */
    private BigDecimal calculateFinalPrice(PricingData pricing, CustomerData customer) {
        if (pricing == null) return BigDecimal.ZERO;

        BigDecimal base = pricing.basePrice();
        BigDecimal discount = pricing.discount();
        BigDecimal taxRate = pricing.taxRate();

        // Apply tier discount
        if (customer != null) {
            switch (customer.tier()) {
                case "GOLD" -> discount = discount.add(new BigDecimal("0.10"));     // 10% extra
                case "PREMIUM" -> discount = discount.add(new BigDecimal("0.05")); // 5% extra
            }
        }

        // Calculate: (base - discount) * (1 + tax)
        BigDecimal discountedPrice = base.subtract(base.multiply(discount));
        BigDecimal finalPrice = discountedPrice.multiply(BigDecimal.ONE.add(taxRate));

        return finalPrice.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Determine order status based on inventory.
     */
    private String determineStatus(InventoryData inventory) {
        if (inventory == null) return "PENDING_INVENTORY";
        if (inventory.quantityAvailable() > 10) return "READY_TO_SHIP";
        if (inventory.quantityAvailable() > 0) return "LOW_STOCK";
        return "BACKORDER";
    }

    /**
     * Output record for processing results.
     */
    public record ProcessingOutput(
            List<ProcessedOrder> successes,
            List<FailedOrder> failures
    ) {}
}

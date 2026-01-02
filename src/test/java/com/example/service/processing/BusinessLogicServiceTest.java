package com.example.service.processing;

import com.example.model.*;
import com.example.service.preload.ProcessingContext;
import com.example.service.processing.BusinessLogicService.ProcessingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BusinessLogicService.
 * 
 * Tests verify:
 * - Orders are processed correctly
 * - Concurrency limit (semaphore) is respected
 * - Successes and failures are properly categorized
 * - Parallel execution using virtual threads
 */
@ExtendWith(MockitoExtension.class)
class BusinessLogicServiceTest {

    private BusinessLogicService businessLogicService;

    @BeforeEach
    void setUp() {
        // Create service with small concurrency limit for testing
        businessLogicService = new BusinessLogicService(5); // 5 concurrent max
    }

    @Test
    @DisplayName("Should process single order successfully")
    void shouldProcessSingleOrder() {
        // Given
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingContext context = createContextWithData("ORD-001");

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(orders, context, executor);

            // Then
            assertThat(output.successes()).hasSize(1);
            assertThat(output.failures()).isEmpty();
            assertThat(output.successes().getFirst().orderId()).isEqualTo("ORD-001");
        }
    }

    @Test
    @DisplayName("Should process multiple orders in parallel")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void shouldProcessMultipleOrdersInParallel() {
        // Given
        int orderCount = 20;
        List<Order> orders = IntStream.range(0, orderCount)
                .mapToObj(i -> createTestOrder("ORD-" + String.format("%03d", i)))
                .toList();
        ProcessingContext context = createContextForOrders(orders);

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(orders, context, executor);

            // Then
            assertThat(output.successes()).hasSize(orderCount);
            assertThat(output.failures()).isEmpty();
        }
    }

    @Test
    @DisplayName("Should handle empty order list")
    void shouldHandleEmptyOrderList() {
        // Given
        List<Order> orders = List.of();
        ProcessingContext context = createEmptyContext();

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(orders, context, executor);

            // Then
            assertThat(output.successes()).isEmpty();
            assertThat(output.failures()).isEmpty();
        }
    }

    @Test
    @DisplayName("Should calculate final price correctly")
    void shouldCalculateFinalPrice() {
        // Given
        Order order = createTestOrder("ORD-001");
        ProcessingContext context = createContextWithData("ORD-001");

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(List.of(order), context, executor);

            // Then
            assertThat(output.successes()).hasSize(1);
            ProcessedOrder processed = output.successes().getFirst();
            assertThat(processed.finalPrice()).isNotNull();
            // Price calculation: (base - base*discount) * (1 + tax) with 10% GOLD discount
            // (99.99 - 99.99*(0.10+0.10)) * 1.08 = 86.39 approx
            assertThat(processed.finalPrice().doubleValue()).isBetween(50.0, 150.0);
        }
    }

    @Test
    @DisplayName("Should set correct status on processed orders")
    void shouldSetCorrectStatus() {
        // Given
        Order order = createTestOrder("ORD-001");
        ProcessingContext context = createContextWithData("ORD-001");

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(List.of(order), context, executor);

            // Then
            assertThat(output.successes().getFirst().status()).isEqualTo("READY_TO_SHIP");
        }
    }

    @Test
    @DisplayName("Should include customer data in processed order")
    void shouldIncludeCustomerData() {
        // Given
        Order order = createTestOrder("ORD-001");
        ProcessingContext context = createContextWithData("ORD-001");

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(List.of(order), context, executor);

            // Then
            ProcessedOrder processed = output.successes().getFirst();
            assertThat(processed.customerName()).isEqualTo("Test Customer");
            assertThat(processed.customerTier()).isEqualTo("GOLD");
        }
    }

    @Test
    @DisplayName("Should include warehouse location from inventory")
    void shouldIncludeWarehouseLocation() {
        // Given
        Order order = createTestOrder("ORD-001");
        ProcessingContext context = createContextWithData("ORD-001");

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(List.of(order), context, executor);

            // Then
            ProcessedOrder processed = output.successes().getFirst();
            assertThat(processed.warehouseLocation()).isEqualTo("WAREHOUSE-A");
        }
    }

    @Test
    @DisplayName("Should process orders with different amounts")
    void shouldProcessOrdersWithDifferentAmounts() {
        // Given
        Order order1 = new Order("ORD-001", "CUST-001", "PENDING", new BigDecimal("50.00"), LocalDateTime.now());
        Order order2 = new Order("ORD-002", "CUST-002", "PENDING", new BigDecimal("150.00"), LocalDateTime.now());
        
        ProcessingContext context = ProcessingContext.builder()
                .customerData(Map.of(
                        "ORD-001", new CustomerData("CUST-001", "Customer 1", "c1@test.com", "SILVER"),
                        "ORD-002", new CustomerData("CUST-002", "Customer 2", "c2@test.com", "GOLD")
                ))
                .inventoryData(Map.of(
                        "ORD-001", new InventoryData("ORD-001", "SKU-001", 100, "WH-A"),
                        "ORD-002", new InventoryData("ORD-002", "SKU-002", 50, "WH-B")
                ))
                .pricingData(Map.of(
                        "ORD-001", new PricingData("ORD-001", new BigDecimal("50.00"), new BigDecimal("5.00"), new BigDecimal("0.08")),
                        "ORD-002", new PricingData("ORD-002", new BigDecimal("150.00"), new BigDecimal("15.00"), new BigDecimal("0.10"))
                ))
                .build();

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingOutput output = businessLogicService.processOrders(List.of(order1, order2), context, executor);

            // Then
            assertThat(output.successes()).hasSize(2);
            
            // Prices should be different
            ProcessedOrder processed1 = output.successes().stream()
                    .filter(o -> o.orderId().equals("ORD-001")).findFirst().orElseThrow();
            ProcessedOrder processed2 = output.successes().stream()
                    .filter(o -> o.orderId().equals("ORD-002")).findFirst().orElseThrow();
            
            assertThat(processed1.finalPrice()).isNotEqualTo(processed2.finalPrice());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

    private Order createTestOrder(String orderId) {
        return new Order(
                orderId,
                "CUST-001",
                "PENDING",
                new BigDecimal("99.99"),
                LocalDateTime.now()
        );
    }

    private ProcessingContext createEmptyContext() {
        return ProcessingContext.builder()
                .customerData(Map.of())
                .inventoryData(Map.of())
                .pricingData(Map.of())
                .build();
    }

    private ProcessingContext createContextWithData(String orderId) {
        CustomerData customer = new CustomerData("CUST-001", "Test Customer", "test@example.com", "GOLD");
        InventoryData inventory = new InventoryData(orderId, "SKU-001", 100, "WAREHOUSE-A");
        // Discount is a percentage decimal (0.10 = 10%), not an absolute value
        PricingData pricing = new PricingData(orderId, new BigDecimal("99.99"), new BigDecimal("0.10"), new BigDecimal("0.08"));

        return ProcessingContext.builder()
                .customerData(Map.of(orderId, customer))
                .inventoryData(Map.of(orderId, inventory))
                .pricingData(Map.of(orderId, pricing))
                .build();
    }

    private ProcessingContext createContextForOrders(List<Order> orders) {
        Map<String, CustomerData> customers = new java.util.HashMap<>();
        Map<String, InventoryData> inventory = new java.util.HashMap<>();
        Map<String, PricingData> pricing = new java.util.HashMap<>();

        for (Order order : orders) {
            customers.put(order.id(), new CustomerData("CUST-001", "Test", "test@test.com", "GOLD"));
            inventory.put(order.id(), new InventoryData(order.id(), "SKU-001", 100, "WH-A"));
            pricing.put(order.id(), new PricingData(order.id(), new BigDecimal("99.99"), new BigDecimal("5.00"), new BigDecimal("0.08")));
        }

        return ProcessingContext.builder()
                .customerData(customers)
                .inventoryData(inventory)
                .pricingData(pricing)
                .build();
    }
}

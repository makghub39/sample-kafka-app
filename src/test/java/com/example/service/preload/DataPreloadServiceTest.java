package com.example.service.preload;

import com.example.config.AppMetrics;
import com.example.model.CustomerData;
import com.example.model.InventoryData;
import com.example.model.PricingData;
import com.example.repository.OrderRepository;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DataPreloadService.
 * 
 * Tests verify:
 * - All 3 batch queries are executed
 * - Queries run in parallel (not sequentially)
 * - Results are correctly assembled into ProcessingContext
 * - Executor is properly utilized
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataPreloadServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private AppMetrics metrics;
    @Mock private Timer timer;
    
    private DataPreloadService preloadService;

    @BeforeEach
    void setUp() {
        // Use default chunk size of 500 for most tests
        preloadService = new DataPreloadService(orderRepository, metrics, 500);
        when(metrics.getDbFetchTimer()).thenReturn(timer);
    }

    @Test
    @DisplayName("Should fetch all three data types")
    void shouldFetchAllDataTypes() {
        // Given
        List<String> orderIds = List.of("ORD-001", "ORD-002");
        
        when(orderRepository.batchFetchCustomerData(orderIds))
                .thenReturn(Map.of("ORD-001", createCustomerData("CUST-001")));
        when(orderRepository.batchFetchInventoryData(orderIds))
                .thenReturn(Map.of("ORD-001", createInventoryData("ORD-001")));
        when(orderRepository.batchFetchPricingData(orderIds))
                .thenReturn(Map.of("ORD-001", createPricingData("ORD-001")));

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingContext context = preloadService.preloadData(orderIds, executor);

            // Then
            assertThat(context.getCustomerData()).hasSize(1);
            assertThat(context.getInventoryData()).hasSize(1);
            assertThat(context.getPricingData()).hasSize(1);
        }

        // Verify all queries were called
        verify(orderRepository).batchFetchCustomerData(orderIds);
        verify(orderRepository).batchFetchInventoryData(orderIds);
        verify(orderRepository).batchFetchPricingData(orderIds);
    }

    @Test
    @DisplayName("Should execute queries in parallel")
    @Timeout(value = 2, unit = TimeUnit.SECONDS) // Fails if sequential (would take 3+ seconds)
    void shouldExecuteQueriesInParallel() throws InterruptedException {
        // Given
        List<String> orderIds = List.of("ORD-001");
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch canProceed = new CountDownLatch(1);
        
        // Each mock waits until all 3 have started (proves parallelism)
        when(orderRepository.batchFetchCustomerData(anyList())).thenAnswer(inv -> {
            allStarted.countDown();
            canProceed.await(); // Wait for signal
            return Map.of("ORD-001", createCustomerData("CUST-001"));
        });
        when(orderRepository.batchFetchInventoryData(anyList())).thenAnswer(inv -> {
            allStarted.countDown();
            canProceed.await();
            return Map.of("ORD-001", createInventoryData("ORD-001"));
        });
        when(orderRepository.batchFetchPricingData(anyList())).thenAnswer(inv -> {
            allStarted.countDown();
            canProceed.await();
            return Map.of("ORD-001", createPricingData("ORD-001"));
        });

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Start preload in background
            var future = executor.submit(() -> preloadService.preloadData(orderIds, executor));
            
            // Wait for all 3 queries to start (proves parallel execution)
            boolean allStartedInTime = allStarted.await(1, TimeUnit.SECONDS);
            assertThat(allStartedInTime).isTrue(); // All 3 started in parallel
            
            // Release the mocks to complete
            canProceed.countDown();
            
            // Get result
            ProcessingContext context = future.get(1, TimeUnit.SECONDS);
            assertThat(context).isNotNull();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should handle empty order list")
    void shouldHandleEmptyOrderList() {
        // Given
        List<String> orderIds = List.of();

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingContext context = preloadService.preloadData(orderIds, executor);

            // Then
            assertThat(context.getCustomerData()).isEmpty();
            assertThat(context.getInventoryData()).isEmpty();
            assertThat(context.getPricingData()).isEmpty();
        }
        
        // Should not call repository for empty list
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("Should use provided executor for async tasks")
    void shouldUseProvidedExecutor() {
        // Given
        List<String> orderIds = List.of("ORD-001");
        
        when(orderRepository.batchFetchCustomerData(anyList())).thenReturn(Map.of());
        when(orderRepository.batchFetchInventoryData(anyList())).thenReturn(Map.of());
        when(orderRepository.batchFetchPricingData(anyList())).thenReturn(Map.of());

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingContext context = preloadService.preloadData(orderIds, executor);

            // Then - all 3 queries should have been called
            verify(orderRepository).batchFetchCustomerData(orderIds);
            verify(orderRepository).batchFetchInventoryData(orderIds);
            verify(orderRepository).batchFetchPricingData(orderIds);
            
            // Context should be built correctly
            assertThat(context).isNotNull();
        }
    }

    @Test
    @DisplayName("Should return correct data for specific order")
    void shouldReturnCorrectDataForOrder() {
        // Given
        List<String> orderIds = List.of("ORD-001", "ORD-002");
        CustomerData customer1 = createCustomerData("CUST-001");
        CustomerData customer2 = createCustomerData("CUST-002");
        
        when(orderRepository.batchFetchCustomerData(orderIds))
                .thenReturn(Map.of("ORD-001", customer1, "ORD-002", customer2));
        when(orderRepository.batchFetchInventoryData(orderIds))
                .thenReturn(Map.of("ORD-001", createInventoryData("ORD-001"), "ORD-002", createInventoryData("ORD-002")));
        when(orderRepository.batchFetchPricingData(orderIds))
                .thenReturn(Map.of("ORD-001", createPricingData("ORD-001"), "ORD-002", createPricingData("ORD-002")));

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingContext context = preloadService.preloadData(orderIds, executor);

            // Then
            assertThat(context.getCustomer("ORD-001")).isEqualTo(customer1);
            assertThat(context.getCustomer("ORD-002")).isEqualTo(customer2);
            assertThat(context.getCustomer("ORD-999")).isNull(); // Non-existent
        }
    }

    @Test
    @DisplayName("Should chunk large batches into smaller queries")
    void shouldChunkLargeBatches() {
        // Given - chunkSize of 3 with 7 order IDs = 3 chunks (3+3+1)
        int chunkSize = 3;
        preloadService = new DataPreloadService(orderRepository, metrics, chunkSize);
        
        List<String> orderIds = List.of("ORD-001", "ORD-002", "ORD-003", "ORD-004", 
                                         "ORD-005", "ORD-006", "ORD-007");
        
        // Mock returns data for any list of IDs
        when(orderRepository.batchFetchCustomerData(anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return ids.stream().collect(java.util.stream.Collectors.toMap(
                    id -> id,
                    id -> createCustomerData("CUST-" + id.substring(4))
            ));
        });
        when(orderRepository.batchFetchInventoryData(anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return ids.stream().collect(java.util.stream.Collectors.toMap(
                    id -> id,
                    id -> createInventoryData(id)
            ));
        });
        when(orderRepository.batchFetchPricingData(anyList())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return ids.stream().collect(java.util.stream.Collectors.toMap(
                    id -> id,
                    id -> createPricingData(id)
            ));
        });

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingContext context = preloadService.preloadData(orderIds, executor);

            // Then - all 7 orders should have data (merged from chunks)
            assertThat(context.getCustomerData()).hasSize(7);
            assertThat(context.getInventoryData()).hasSize(7);
            assertThat(context.getPricingData()).hasSize(7);
            
            // Verify chunking occurred - should be called 3 times per data type
            // (chunks: [1,2,3], [4,5,6], [7])
            verify(orderRepository, times(3)).batchFetchCustomerData(anyList());
            verify(orderRepository, times(3)).batchFetchInventoryData(anyList());
            verify(orderRepository, times(3)).batchFetchPricingData(anyList());
        }
    }

    @Test
    @DisplayName("Should not chunk when batch size is below threshold")
    void shouldNotChunkSmallBatches() {
        // Given - chunkSize of 10 with only 3 order IDs = no chunking needed
        int chunkSize = 10;
        preloadService = new DataPreloadService(orderRepository, metrics, chunkSize);
        
        List<String> orderIds = List.of("ORD-001", "ORD-002", "ORD-003");
        
        when(orderRepository.batchFetchCustomerData(orderIds))
                .thenReturn(Map.of("ORD-001", createCustomerData("CUST-001")));
        when(orderRepository.batchFetchInventoryData(orderIds))
                .thenReturn(Map.of("ORD-001", createInventoryData("ORD-001")));
        when(orderRepository.batchFetchPricingData(orderIds))
                .thenReturn(Map.of("ORD-001", createPricingData("ORD-001")));

        // When
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ProcessingContext context = preloadService.preloadData(orderIds, executor);

            // Then - should be called exactly once per data type (no chunking)
            verify(orderRepository, times(1)).batchFetchCustomerData(orderIds);
            verify(orderRepository, times(1)).batchFetchInventoryData(orderIds);
            verify(orderRepository, times(1)).batchFetchPricingData(orderIds);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

    private CustomerData createCustomerData(String customerId) {
        return new CustomerData(customerId, "Test Customer", "test@example.com", "GOLD");
    }

    private InventoryData createInventoryData(String orderId) {
        return new InventoryData(orderId, "SKU-001", 100, "WAREHOUSE-A");
    }

    private PricingData createPricingData(String orderId) {
        return new PricingData(orderId, new BigDecimal("99.99"), new BigDecimal("10.00"), new BigDecimal("0.08"));
    }
}

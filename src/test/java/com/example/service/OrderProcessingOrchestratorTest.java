package com.example.service;

import com.example.config.AppMetrics;
import com.example.model.*;
import com.example.service.cache.CachingDataService;
import com.example.service.preload.DataPreloadService;
import com.example.service.preload.ProcessingContext;
import com.example.service.processing.BusinessLogicService;
import com.example.service.processing.BusinessLogicService.ProcessingOutput;
import com.example.service.publishing.PublishingService;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderProcessingOrchestrator.
 * 
 * Tests verify:
 * - Pipeline stages execute in correct order
 * - Executor is passed to all services
 * - Results are properly aggregated
 * - Metrics are recorded
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderProcessingOrchestratorTest {

    @Mock private DataPreloadService preloadService;
    @Mock private CachingDataService cachingDataService;
    @Mock private BusinessLogicService businessLogicService;
    @Mock private PublishingService publishingService;
    @Mock private AppMetrics metrics;
    @Mock private Timer timer;
    @Mock private ExecutorService executorService;
    private OrderProcessingOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new OrderProcessingOrchestrator(
                preloadService, cachingDataService, businessLogicService, publishingService, metrics,executorService
        );
        // Disable cache for tests (use direct preload service)
        ReflectionTestUtils.setField(orchestrator, "dataCacheEnabled", false);
        
        // Setup timer mocks
        when(metrics.getProcessingTimer()).thenReturn(timer);
        when(metrics.getTotalProcessingTimer()).thenReturn(timer);
    }

    @Test
    @DisplayName("Should return empty result for empty order list")
    void shouldReturnEmptyResultForEmptyOrders() {
        // When
        ProcessingResult result = orchestrator.processOrders(List.of());

        // Then
        assertThat(result.successes()).isEmpty();
        assertThat(result.failures()).isEmpty();
        assertThat(result.processingTimeMs()).isZero();
        
        // Verify no services were called
        verifyNoInteractions(preloadService, businessLogicService, publishingService);
    }

    @Test
    @DisplayName("Should execute all pipeline stages in order")
    void shouldExecuteStagesInOrder() {
        // Given
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingContext context = createEmptyContext();
        ProcessingOutput output = new ProcessingOutput(List.of(createProcessedOrder("ORD-001")), List.of());
        
        when(preloadService.preloadData(anyList(), any(ExecutorService.class))).thenReturn(context);
        when(businessLogicService.processOrders(anyList(), any(), any(ExecutorService.class))).thenReturn(output);

        // When
        orchestrator.processOrders(orders, false);

        // Then - verify order of execution
        InOrder inOrder = inOrder(preloadService, businessLogicService, publishingService);
        inOrder.verify(preloadService).preloadData(anyList(), any(ExecutorService.class));
        inOrder.verify(businessLogicService).processOrders(anyList(), any(), any(ExecutorService.class));
        inOrder.verify(publishingService).publish(anyList(), eq(false), any(ExecutorService.class));
    }

    @Test
    @DisplayName("Should pass same executor to all services")
    void shouldPassExecutorToAllServices() {
        // Given
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingContext context = createEmptyContext();
        ProcessingOutput output = new ProcessingOutput(List.of(createProcessedOrder("ORD-001")), List.of());
        
        when(preloadService.preloadData(anyList(), any(ExecutorService.class))).thenReturn(context);
        when(businessLogicService.processOrders(anyList(), any(), any(ExecutorService.class))).thenReturn(output);

        // When
        orchestrator.processOrders(orders, false);

        // Then - verify all services received an executor
        verify(preloadService).preloadData(anyList(), any(ExecutorService.class));
        verify(businessLogicService).processOrders(anyList(), any(), any(ExecutorService.class));
        verify(publishingService).publish(anyList(), anyBoolean(), any(ExecutorService.class));
    }

    @Test
    @DisplayName("Should pass useGrouping flag to publishing service")
    void shouldPassGroupingFlagToPublisher() {
        // Given
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingContext context = createEmptyContext();
        ProcessingOutput output = new ProcessingOutput(List.of(createProcessedOrder("ORD-001")), List.of());
        
        when(preloadService.preloadData(anyList(), any(ExecutorService.class))).thenReturn(context);
        when(businessLogicService.processOrders(anyList(), any(), any(ExecutorService.class))).thenReturn(output);

        // When - with grouping enabled
        orchestrator.processOrders(orders, true);

        // Then
        verify(publishingService).publish(anyList(), eq(true), any(ExecutorService.class));
    }

    @Test
    @DisplayName("Should aggregate successes and failures in result")
    void shouldAggregateResults() {
        // Given
        List<Order> orders = List.of(
                createTestOrder("ORD-001"),
                createTestOrder("ORD-002"),
                createTestOrder("ORD-003")
        );
        ProcessingContext context = createEmptyContext();
        
        List<ProcessedOrder> successes = List.of(
                createProcessedOrder("ORD-001"),
                createProcessedOrder("ORD-002")
        );
        List<FailedOrder> failures = List.of(
                new FailedOrder(createTestOrder("ORD-003"), "Processing error", "RuntimeException")
        );
        ProcessingOutput output = new ProcessingOutput(successes, failures);
        
        when(preloadService.preloadData(anyList(), any(ExecutorService.class))).thenReturn(context);
        when(businessLogicService.processOrders(anyList(), any(), any(ExecutorService.class))).thenReturn(output);

        // When
        ProcessingResult result = orchestrator.processOrders(orders, false);

        // Then
        assertThat(result.successes()).hasSize(2);
        assertThat(result.failures()).hasSize(1);
        assertThat(result.processingTimeMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Should record metrics for successful processing")
    void shouldRecordMetrics() {
        // Given
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingContext context = createEmptyContext();
        ProcessingOutput output = new ProcessingOutput(List.of(createProcessedOrder("ORD-001")), List.of());
        
        when(preloadService.preloadData(anyList(), any(ExecutorService.class))).thenReturn(context);
        when(businessLogicService.processOrders(anyList(), any(), any(ExecutorService.class))).thenReturn(output);

        // When
        orchestrator.processOrders(orders, false);

        // Then
        verify(metrics).incrementOrdersProcessed(1);
        verify(metrics).incrementOrdersFailed(0);
        verify(metrics.getTotalProcessingTimer(), atLeast(1)).record(anyLong(), any());
    }

    @Test
    @DisplayName("Should extract order IDs for preload service")
    void shouldExtractOrderIdsForPreload() {
        // Given
        List<Order> orders = List.of(
                createTestOrder("ORD-001"),
                createTestOrder("ORD-002")
        );
        ProcessingContext context = createEmptyContext();
        ProcessingOutput output = new ProcessingOutput(List.of(), List.of());
        
        when(preloadService.preloadData(anyList(), any(ExecutorService.class))).thenReturn(context);
        when(businessLogicService.processOrders(anyList(), any(), any(ExecutorService.class))).thenReturn(output);

        // When
        orchestrator.processOrders(orders, false);

        // Then
        verify(preloadService).preloadData(eq(List.of("ORD-001", "ORD-002")), any(ExecutorService.class));
    }

    @Test
    @DisplayName("Should use default no-grouping when calling single-arg method")
    void shouldDefaultToNoGrouping() {
        // Given
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingContext context = createEmptyContext();
        ProcessingOutput output = new ProcessingOutput(List.of(createProcessedOrder("ORD-001")), List.of());
        
        when(preloadService.preloadData(anyList(), any(ExecutorService.class))).thenReturn(context);
        when(businessLogicService.processOrders(anyList(), any(), any(ExecutorService.class))).thenReturn(output);

        // When - single arg method (defaults to no grouping)
        orchestrator.processOrders(orders);

        // Then
        verify(publishingService).publish(anyList(), eq(false), any(ExecutorService.class));
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

    private ProcessedOrder createProcessedOrder(String orderId) {
        return new ProcessedOrder(
                orderId,
                "CUST-001",
                "Test Customer",
                "GOLD",
                new BigDecimal("109.99"),
                "WAREHOUSE-A",
                "COMPLETED",
                LocalDateTime.now(),
                "system"
        );
    }

    private ProcessingContext createEmptyContext() {
        return ProcessingContext.builder()
                .customerData(Map.of())
                .inventoryData(Map.of())
                .pricingData(Map.of())
                .build();
    }
}

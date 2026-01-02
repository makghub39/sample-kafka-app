package com.example.service.publishing;

import com.example.config.AppMetrics;
import com.example.model.ProcessedOrder;
import com.example.service.WmqPublisher;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PublishingService.
 * 
 * Tests verify:
 * - Correct delegation to WmqPublisher
 * - Grouping flag is passed correctly
 * - Metrics are recorded
 * - Empty list handling
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublishingServiceTest {

    @Mock private WmqPublisher wmqPublisher;
    @Mock private AppMetrics metrics;
    @Mock private Timer timer;

    private PublishingService publishingService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        publishingService = new PublishingService(wmqPublisher, metrics);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        when(metrics.getWmqPublishTimer()).thenReturn(timer);
    }
    
    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("Should delegate to WmqPublisher with grouping enabled")
    void shouldDelegateWithGrouping() {
        // Given
        List<ProcessedOrder> orders = List.of(createProcessedOrder("ORD-001"));

        // When
        publishingService.publish(orders, true, executor);

        // Then
        verify(wmqPublisher).sendBatch(eq(orders), eq(executor));
        verify(wmqPublisher, never()).sendBatchWithoutGrouping(anyList(), any());
    }

    @Test
    @DisplayName("Should delegate to WmqPublisher without grouping")
    void shouldDelegateWithoutGrouping() {
        // Given
        List<ProcessedOrder> orders = List.of(createProcessedOrder("ORD-001"));

        // When
        publishingService.publish(orders, false, executor);

        // Then
        verify(wmqPublisher).sendBatchWithoutGrouping(eq(orders), eq(executor));
        verify(wmqPublisher, never()).sendBatch(anyList(), any());
    }

    @Test
    @DisplayName("Should not call publisher for empty list")
    void shouldNotCallPublisherForEmptyList() {
        // When
        publishingService.publish(List.of(), false, executor);

        // Then
        verifyNoInteractions(wmqPublisher);
    }

    @Test
    @DisplayName("Should record publish timer")
    void shouldRecordPublishTimer() {
        // Given
        List<ProcessedOrder> orders = List.of(createProcessedOrder("ORD-001"));

        // When
        publishingService.publish(orders, false, executor);

        // Then
        verify(metrics.getWmqPublishTimer()).record(anyLong(), any());
    }

    @Test
    @DisplayName("Should pass executor to WmqPublisher")
    void shouldPassExecutorToWmqPublisher() {
        // Given
        List<ProcessedOrder> orders = List.of(createProcessedOrder("ORD-001"));

        // When
        publishingService.publish(orders, true, executor);

        // Then
        verify(wmqPublisher).sendBatch(anyList(), eq(executor));
    }

    @Test
    @DisplayName("publishGrouped should call publish with grouping true")
    void publishGroupedShouldEnableGrouping() {
        // Given
        List<ProcessedOrder> orders = List.of(createProcessedOrder("ORD-001"));

        // When
        publishingService.publishGrouped(orders, executor);

        // Then
        verify(wmqPublisher).sendBatch(eq(orders), eq(executor));
    }

    @Test
    @DisplayName("publishIndividual should call publish with grouping false")
    void publishIndividualShouldDisableGrouping() {
        // Given
        List<ProcessedOrder> orders = List.of(createProcessedOrder("ORD-001"));

        // When
        publishingService.publishIndividual(orders, executor);

        // Then
        verify(wmqPublisher).sendBatchWithoutGrouping(eq(orders), eq(executor));
    }

    @Test
    @DisplayName("Should handle multiple orders")
    void shouldHandleMultipleOrders() {
        // Given
        List<ProcessedOrder> orders = List.of(
                createProcessedOrder("ORD-001"),
                createProcessedOrder("ORD-002"),
                createProcessedOrder("ORD-003")
        );

        // When
        publishingService.publish(orders, false, executor);

        // Then
        verify(wmqPublisher).sendBatchWithoutGrouping(argThat(list -> list.size() == 3), eq(executor));
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

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
}

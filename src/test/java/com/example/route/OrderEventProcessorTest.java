package com.example.route;

import com.example.config.AppMetrics;
import com.example.model.*;
import com.example.service.DeadLetterPublisher;
import com.example.service.OrderFetchService;
import com.example.service.OrderProcessingOrchestrator;
import com.example.service.PartnerValidationService;
import com.example.service.cache.EventDeduplicationService;
import io.micrometer.core.instrument.Timer;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.consumer.KafkaManualCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrderEventProcessor (Camel Processor).
 * 
 * Tests verify:
 * - Event extraction from Camel Exchange
 * - Delegation to services
 * - Kafka offset commit
 * - Error handling
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderEventProcessorTest {

    @Mock private OrderFetchService orderFetchService;
    @Mock private OrderProcessingOrchestrator processingOrchestrator;
    @Mock private DeadLetterPublisher deadLetterPublisher;
    @Mock private EventDeduplicationService deduplicationService;
    @Mock private PartnerValidationService partnerValidationService;
    @Mock private AppMetrics metrics;
    @Mock private Timer timer;
    @Mock private Exchange exchange;
    @Mock private Message message;
    @Mock private KafkaManualCommit manualCommit;

    private OrderEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OrderEventProcessor(
                orderFetchService, processingOrchestrator, deadLetterPublisher, 
                deduplicationService,partnerValidationService, metrics
        );
        
        // Setup common mocks
        when(exchange.getIn()).thenReturn(message);
        when(metrics.getTotalEventTimer()).thenReturn(timer);
        // Default: allow all events (not duplicates)
        when(deduplicationService.tryAcquire(any(OrderEvent.class))).thenReturn(true);
    }

    @Test
    @DisplayName("Should process event and commit Kafka offset")
    void shouldProcessEventAndCommitOffset() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingResult result = new ProcessingResult(List.of(createProcessedOrder("ORD-001")), List.of(), 100);
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(orders);
        when(processingOrchestrator.processOrders(eq(orders), anyBoolean())).thenReturn(result);

        // When
        processor.process(exchange);

        // Then
        InOrder inOrder = inOrder(orderFetchService, processingOrchestrator, manualCommit);
        inOrder.verify(orderFetchService).fetchOrdersForEvent(event);
        inOrder.verify(processingOrchestrator).processOrders(orders, false);
        inOrder.verify(manualCommit).commit();
    }

    @Test
    @DisplayName("Should skip processing for empty orders and commit offset")
    void shouldSkipProcessingForEmptyOrders() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(List.of());

        // When
        processor.process(exchange);

        // Then
        verify(orderFetchService).fetchOrdersForEvent(event);
        verifyNoInteractions(processingOrchestrator);
        verify(manualCommit).commit();
    }

    @Test
    @DisplayName("Should send failures to dead letter publisher")
    void shouldSendFailuresToDeadLetter() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        List<Order> orders = List.of(createTestOrder("ORD-001"), createTestOrder("ORD-002"));
        List<FailedOrder> failures = List.of(new FailedOrder(createTestOrder("ORD-002"), "Processing error", "RuntimeException"));
        ProcessingResult result = new ProcessingResult(
                List.of(createProcessedOrder("ORD-001")), 
                failures, 
                100
        );
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(orders);
        when(processingOrchestrator.processOrders(eq(orders), anyBoolean())).thenReturn(result);

        // When
        processor.process(exchange);

        // Then
        verify(deadLetterPublisher).send(failures);
    }

    @Test
    @DisplayName("Should not call dead letter publisher when no failures")
    void shouldNotCallDeadLetterWhenNoFailures() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingResult result = new ProcessingResult(
                List.of(createProcessedOrder("ORD-001")), 
                List.of(), // No failures
                100
        );
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(orders);
        when(processingOrchestrator.processOrders(eq(orders), anyBoolean())).thenReturn(result);

        // When
        processor.process(exchange);

        // Then
        verifyNoInteractions(deadLetterPublisher);
    }

    @Test
    @DisplayName("Should pass grouping flag from event")
    void shouldPassGroupingFlagFromEvent() throws Exception {
        // Given - event with grouping required (BULK_ORDER type)
        OrderEvent event = new OrderEvent(
                "EVT-001",
                "BULK_ORDER",  // Event type that requires grouping
                "ACME-CORP",
                "WEST-REGION"
        );
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingResult result = new ProcessingResult(List.of(createProcessedOrder("ORD-001")), List.of(), 100);
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(orders);
        when(processingOrchestrator.processOrders(eq(orders), anyBoolean())).thenReturn(result);

        // When
        processor.process(exchange);

        // Then - verify grouping flag based on event type (BULK_ORDER requires grouping)
        verify(processingOrchestrator).processOrders(eq(orders), eq(true));
    }

    @Test
    @DisplayName("Should increment Kafka batch metric")
    void shouldIncrementKafkaBatchMetric() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(List.of());

        // When
        processor.process(exchange);

        // Then
        verify(metrics).incrementKafkaBatch();
    }

    @Test
    @DisplayName("Should record total event timer")
    void shouldRecordTotalEventTimer() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingResult result = new ProcessingResult(List.of(createProcessedOrder("ORD-001")), List.of(), 100);
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(orders);
        when(processingOrchestrator.processOrders(eq(orders), anyBoolean())).thenReturn(result);

        // When
        processor.process(exchange);

        // Then
        verify(metrics.getTotalEventTimer()).record(anyLong(), any());
    }

    @Test
    @DisplayName("Should set eventId header on exchange")
    void shouldSetEventIdHeader() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        List<Order> orders = List.of(createTestOrder("ORD-001"));
        ProcessingResult result = new ProcessingResult(List.of(createProcessedOrder("ORD-001")), List.of(), 100);
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(orders);
        when(processingOrchestrator.processOrders(eq(orders), anyBoolean())).thenReturn(result);

        // When
        processor.process(exchange);

        // Then
        verify(message).setHeader("eventId", event.eventId());
    }

    @Test
    @DisplayName("Should handle missing manual commit header gracefully")
    void shouldHandleMissingManualCommitHeader() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(null); // No manual commit
        when(orderFetchService.fetchOrdersForEvent(event)).thenReturn(List.of());

        // When - should not throw
        processor.process(exchange);

        // Then - processing completes without error
        verify(orderFetchService).fetchOrdersForEvent(event);
    }

    @Test
    @DisplayName("Should re-throw exception on processing failure")
    void shouldReThrowExceptionOnFailure() {
        // Given
        OrderEvent event = createTestEvent();
        RuntimeException error = new RuntimeException("MongoDB connection failed");
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(orderFetchService.fetchOrdersForEvent(event)).thenThrow(error);

        // When/Then
        assertThatThrownBy(() -> processor.process(exchange))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("MongoDB connection failed");
    }

    @Test
    @DisplayName("Should skip duplicate events and still commit offset")
    void shouldSkipDuplicateEvents() throws Exception {
        // Given
        OrderEvent event = createTestEvent();
        
        when(message.getBody(OrderEvent.class)).thenReturn(event);
        when(message.getHeader(KafkaConstants.MANUAL_COMMIT)).thenReturn(manualCommit);
        // Mark as duplicate
        when(deduplicationService.tryAcquire(event)).thenReturn(false);

        // When
        processor.process(exchange);

        // Then
        // Should NOT call any processing services
        verifyNoInteractions(orderFetchService);
        verifyNoInteractions(processingOrchestrator);
        verifyNoInteractions(deadLetterPublisher);
        
        // Should increment duplicate counter
        verify(metrics).incrementDuplicateEvents();
        
        // Should still commit Kafka offset to avoid reprocessing
        verify(manualCommit).commit();
    }

    // ═══════════════════════════════════════════════════════════════
    // Helper Methods
    // ═══════════════════════════════════════════════════════════════

    private OrderEvent createTestEvent() {
        return new OrderEvent(
                "EVT-001",
                "ORDER_CREATED",
                "ACME-CORP",
                "WEST-REGION"
        );
    }

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
}

package com.example.route;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TraceIdProcessorTest {

    @Mock
    private Exchange exchange;

    @Mock
    private Message message;

    private TraceIdProcessor traceIdProcessor;

    private Map<String, Object> headers;

    @BeforeEach
    void setUp() {
        traceIdProcessor = new TraceIdProcessor(null);
        headers = new HashMap<>();

        lenient().when(exchange.getIn()).thenReturn(message);

        // Mock getHeader to read from our local headers map
        lenient().when(message.getHeader(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return (String) headers.get(key);
        });
        // Provide a stable exchangeId from the mock
        lenient().when(exchange.getExchangeId()).thenReturn("ex-123");
        
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("Should generate new traceId and spanId if none exist")
    void shouldGenerateNewIdsWhenNoneExist() throws Exception {
        // When
        traceIdProcessor.process(exchange);

        // Then
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        String exchangeId = MDC.get("exchangeId");

        assertThat(traceId).isNotNull().hasSize(32);
        assertThat(spanId).isNotNull().hasSize(16);
        assertThat(exchangeId).isEqualTo("ex-123");

        verify(exchange).setProperty("traceId", traceId);
        verify(exchange).setProperty("spanId", spanId);
        verify(message).setHeader("X-Trace-Id", traceId);
        verify(message).setHeader("X-Exchange-Id", "ex-123");
    }

    @Test
    @DisplayName("Should use traceId from X-Trace-Id header if present")
    void shouldUseTraceIdFromXTraceIdHeader() throws Exception {
        // Given
        String existingTraceId = UUID.randomUUID().toString().replace("-", "");
        headers.put("X-Trace-Id", existingTraceId);

        // When
        traceIdProcessor.process(exchange);

        // Then
        assertThat(MDC.get("traceId")).isEqualTo(existingTraceId);
        assertThat(MDC.get("spanId")).isNotNull();

        assertThat(MDC.get("exchangeId")).isEqualTo("ex-123");

        verify(exchange).setProperty("traceId", existingTraceId);
    }
    
    @Test
    @DisplayName("Should use traceId from 'traceId' header if present")
    void shouldUseTraceIdFromTraceIdHeader() throws Exception {
        // Given
        String existingTraceId = UUID.randomUUID().toString().replace("-", "");
        headers.put("traceId", existingTraceId);

        // When
        traceIdProcessor.process(exchange);

        // Then
        assertThat(MDC.get("traceId")).isEqualTo(existingTraceId);
        assertThat(MDC.get("spanId")).isNotNull();

        assertThat(MDC.get("exchangeId")).isEqualTo("ex-123");

        verify(exchange).setProperty("traceId", existingTraceId);
    }

    @Test
    @DisplayName("X-Trace-Id header should have precedence over 'traceId' header")
    void xTraceIdHeaderShouldHavePrecedence() throws Exception {
        // Given
        String xTraceId = UUID.randomUUID().toString().replace("-", "");
        String traceIdHeader = UUID.randomUUID().toString().replace("-", "");
        headers.put("X-Trace-Id", xTraceId);
        headers.put("traceId", traceIdHeader);

        // When
        traceIdProcessor.process(exchange);

        // Then
        assertThat(MDC.get("traceId")).isEqualTo(xTraceId);
        assertThat(MDC.get("exchangeId")).isEqualTo("ex-123");
        verify(exchange).setProperty("traceId", xTraceId);
    }

    @Test
    @DisplayName("clearTraceContext should remove ids from MDC")
    void clearTraceContextShouldRemoveIdsFromMdc() {
        // Given
        MDC.put("traceId", "some-trace-id");
        MDC.put("spanId", "some-span-id");

        // When
        TraceIdProcessor.clearTraceContext();

        // Then
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("spanId")).isNull();
    }
}

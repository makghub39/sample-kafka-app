package com.example.config;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TraceContextManagerTest {

    @BeforeEach
    void before() {
        // ensure clean MDC
        TraceContextManager.clear();
    }

    @AfterEach
    void after() {
        TraceContextManager.clear();
    }

    @Test
    void ensureForHttp_populatesMdcAndResponseHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);

        when(req.getHeader(TraceContextManager.TRACE_HEADER)).thenReturn(null);

        String traceId = TraceContextManager.ensureForHttp(req, resp);

        assertNotNull(traceId);
        assertEquals(32, traceId.length());

        String mdcTrace = MDC.get(TraceContextManager.TRACE_ID);
        String mdcSpan = MDC.get(TraceContextManager.SPAN_ID);

        assertNotNull(mdcTrace);
        assertNotNull(mdcSpan);
        assertEquals(traceId, mdcTrace);
        assertEquals(16, mdcSpan.length());

        verify(resp).setHeader(TraceContextManager.TRACE_HEADER, traceId);
    }

    @Test
    void ensureForExchange_populatesMdcAndExchangeProperties() throws Exception {
        org.apache.camel.Exchange exchange = mock(org.apache.camel.Exchange.class);
        org.apache.camel.Message in = mock(org.apache.camel.Message.class);

        Map<String, Object> properties = new java.util.HashMap<>();
        Map<String, Object> headers = new java.util.HashMap<>();

        when(exchange.getIn()).thenReturn(in);
        when(exchange.getExchangeId()).thenReturn("ex-1");

        // capture setProperty/getProperty
        doAnswer(inv -> {
            String k = inv.getArgument(0);
            Object v = inv.getArgument(1);
            properties.put(k, v);
            return null;
        }).when(exchange).setProperty(anyString(), any());
        when(exchange.getProperty(anyString())).thenAnswer(inv -> properties.get(inv.getArgument(0)));

        // capture headers
        doAnswer(inv -> {
            String k = inv.getArgument(0);
            Object v = inv.getArgument(1);
            headers.put(k, v);
            return null;
        }).when(in).setHeader(anyString(), any());
        when(in.getHeader(anyString(), eq(String.class))).thenAnswer(inv -> (String) headers.get(inv.getArgument(0)));

        String traceId = TraceContextManager.ensureForExchange(exchange);

        assertNotNull(traceId);
        assertEquals(32, traceId.length());

        assertEquals(traceId, MDC.get(TraceContextManager.TRACE_ID));
        assertNotNull(MDC.get(TraceContextManager.SPAN_ID));

        assertEquals(traceId, properties.get(TraceContextManager.TRACE_ID));
        assertEquals(traceId, headers.get(TraceContextManager.TRACE_HEADER));
    }
}

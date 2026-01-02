package com.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.slf4j.MDC;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TraceIdFilterTest {

    private TraceIdFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
        TraceContextManager.clear();
    }

    @AfterEach
    void tearDown() {
        TraceContextManager.clear();
    }

    @Test
    void doFilter_setsTraceIdAndCleansUp() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        when(req.getHeader(TraceContextManager.TRACE_HEADER)).thenReturn(null);

        // When the chain is invoked, traceId should be present in MDC
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) {
                String traceId = MDC.get(TraceContextManager.TRACE_ID);
                assertNotNull(traceId);
                assertEquals(32, traceId.length());
                return null;
            }
        }).when(chain).doFilter(req, resp);

        filter.doFilter(req, resp, chain);

        // After filter completes, MDC should be cleared
        assertNull(MDC.get(TraceContextManager.TRACE_ID));

        // Response header should have been set
        verify(resp).setHeader(eq(TraceContextManager.TRACE_HEADER), anyString());
    }
}

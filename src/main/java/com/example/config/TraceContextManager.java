package com.example.config;

import org.apache.camel.Exchange;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * Centralized trace context helper used by HTTP filter and Camel processors.
 * Ensures consistent generation, MDC population, header propagation and cleanup.
 */
public final class TraceContextManager {

    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceContextManager() {}

    public static String ensureForHttp(HttpServletRequest request, HttpServletResponse response) {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = MDC.get(TRACE_ID);
        }
        if (traceId == null || traceId.isEmpty()) {
            traceId = generateTraceId();
        }
        String spanId = MDC.get(SPAN_ID);
        if (spanId == null || spanId.isEmpty()) {
            spanId = generateSpanId();
        }

        MDC.put(TRACE_ID, traceId);
        MDC.put(SPAN_ID, spanId);

        if (response != null) {
            response.setHeader(TRACE_HEADER, traceId);
        }

        return traceId;
    }

    public static String ensureForExchange(Exchange exchange) {
        String traceId = exchange.getIn().getHeader(TRACE_HEADER, String.class);
        if (traceId == null || traceId.isEmpty()) {
            traceId = exchange.getIn().getHeader(TRACE_ID, String.class);
        }
        if (traceId == null || traceId.isEmpty()) {
            traceId = MDC.get(TRACE_ID);
        }
        if (traceId == null || traceId.isEmpty()) {
            traceId = generateTraceId();
        }

        String spanId = exchange.getIn().getHeader(SPAN_ID, String.class);
        if (spanId == null || spanId.isEmpty()) {
            spanId = MDC.get(SPAN_ID);
        }
        if (spanId == null || spanId.isEmpty()) {
            spanId = generateSpanId();
        }

        MDC.put(TRACE_ID, traceId);
        MDC.put(SPAN_ID, spanId);

        exchange.setProperty(TRACE_ID, traceId);
        exchange.setProperty(SPAN_ID, spanId);
        exchange.getIn().setHeader(TRACE_HEADER, traceId);
        exchange.getIn().setHeader(SPAN_ID, spanId);

        return traceId;
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(SPAN_ID);
        MDC.remove("exchangeId");
    }
}

package com.example.route;

import com.example.config.TraceContextManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;

/**
 * Camel processor that sets up trace IDs for Kafka messages and enriches the
 * active span with Camel exchangeId. This ensures all logs during Kafka
 * message processing have correlated trace IDs and exchange identifiers.
 *
 * Should be added at the start of Kafka consumer routes.
 */
@Component("traceIdProcessor")
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class TraceIdProcessor implements Processor {

    private static final String TRACE_ID = "traceId";
    private static final String SPAN_ID = "spanId";

    private final Tracer tracer;

    public TraceIdProcessor(@Nullable Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        // Ensure traceId/spanId are present and populated in MDC and exchange
        TraceContextManager.ensureForExchange(exchange);

        // Enrich with Camel exchangeId for route-level debugging
        String exchangeId = exchange.getExchangeId();
        MDC.put("exchangeId", exchangeId);
        exchange.setProperty("exchangeId", exchangeId);
        exchange.getIn().setHeader("X-Exchange-Id", exchangeId);

        // Tag current span if tracer available
        try {
            if (tracer != null) {
                Span current = tracer.currentSpan();
                if (current != null) {
                    current.tag("camel.exchange_id", exchangeId);
                    try {
                        current.name("camel.exchange:" + exchangeId);
                    } catch (Throwable ignored) {
                    }
                } else {
                    // Create a span for this exchange so we can tag it and close later
                    Span created = tracer.nextSpan().name("camel.exchange:" + exchangeId);
                    created.start();
                    // put span in scope so it becomes current
                    Tracer.SpanInScope scope = tracer.withSpan(created);
                    // store created span and scope on exchange for later cleanup
                    try {
                        exchange.setProperty("createdSpan", created);
                        exchange.setProperty("createdSpanScope", scope);
                        created.tag("camel.exchange_id", exchangeId);
                    } catch (Throwable ex) {
                        log.debug("Failed to store created span on exchange", ex);
                    }
                }
            }
        } catch (Throwable t) {
            log.debug("Failed to tag or create span with exchangeId", t);
        }
    }

    /**
     * Call this to clean up MDC after processing completes.
     */
    public static void clearTraceContext(org.apache.camel.Exchange exchange) {
        // End any created span and close its scope
        if (exchange != null) {
            try {
                Object scopeObj = exchange.getProperty("createdSpanScope");
                if (scopeObj instanceof Tracer.SpanInScope) {
                    try {
                        ((Tracer.SpanInScope) scopeObj).close();
                    } catch (Throwable ignore) {
                    }
                }

                Object spanObj = exchange.getProperty("createdSpan");
                if (spanObj instanceof Span) {
                    try {
                        ((Span) spanObj).end();
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable t) {
                log.debug("Error while ending created span", t);
            }
        }

        TraceContextManager.clear();
    }
}

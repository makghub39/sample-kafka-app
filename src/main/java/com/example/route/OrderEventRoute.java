package com.example.route;

import com.example.model.OrderEvent;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Apache Camel route for consuming Kafka order events.
 * Replaces the Spring @KafkaListener with a more flexible and configurable route.
 * 
 * Benefits of Camel over @KafkaListener:
 * - Declarative route definition with DSL
 * - Built-in error handling patterns (DLQ, retry, circuit breaker)
 * - Easy integration with other systems (JMS, REST, etc.)
 * - Route-level metrics and tracing
 * - Visual route debugging in Hawtio
 * 
 * Flow:
 * 1. Consume from Kafka topic
 * 2. Deserialize JSON to OrderEvent
 * 3. Process via OrderEventProcessor (calls existing OrderProcessor)
 * 4. Handle errors with DLQ
 */
@Component
public class OrderEventRoute extends RouteBuilder {

    @Value("${app.kafka.topic.order-events:order-events}")
    private String orderEventsTopic;
    
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;
    
    @Value("${spring.kafka.consumer.group-id:order-processor-group}")
    private String consumerGroupId;
    
    @Value("${spring.kafka.consumer.max-poll-records:500}")
    private int maxPollRecords;

    @Value("${camel.route.autostart:false}")
    private boolean autoStartRoute;

    @Override
    public void configure() throws Exception {
        
        // ═══════════════════════════════════════════════════════════════
        // Global error handler with Dead Letter Channel
        // ═══════════════════════════════════════════════════════════════
        errorHandler(deadLetterChannel("direct:dlq")
                .maximumRedeliveries(3)
                .redeliveryDelay(1000)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .logRetryAttempted(true)
                .logExhausted(true)
                .useOriginalMessage());

        // ═══════════════════════════════════════════════════════════════
        // Dead Letter Queue Route
        // ═══════════════════════════════════════════════════════════════
        from("direct:dlq")
                .routeId("order-dlq-route")
                .log(LoggingLevel.ERROR, "╔══════════════════════════════════════════════════════════════╗")
                .log(LoggingLevel.ERROR, "║ DLQ: Failed to process event after retries                   ║")
                .log(LoggingLevel.ERROR, "║ Exception: ${exception.message}                              ║")
                .log(LoggingLevel.ERROR, "╚══════════════════════════════════════════════════════════════╝")
                .process("deadLetterProcessor");

        // ═══════════════════════════════════════════════════════════════
        // Main Kafka Consumer Route
        // ═══════════════════════════════════════════════════════════════
        from(buildKafkaUri())
                .routeId("order-kafka-consumer")
                .log("Auto Startup: " + autoStartRoute)
                .autoStartup(autoStartRoute)
                
                                // Set up trace ID and exchangeId for logging correlation (MUST be first)
                                .process("traceIdProcessor")

                                // Ensure trace context is cleared after the exchange completes (success or failure)
                                .onCompletion()
                .log("Clearing trace context ,Completed exchange with ID: {}  ${exchangeId}")
                                        .process(exchange -> TraceIdProcessor.clearTraceContext(exchange))
                                .end()
                
                .log(LoggingLevel.INFO, "╔══════════════════════════════════════════════════════════════╗")
                .log(LoggingLevel.INFO, "║ Received Kafka message from topic: ${headers.kafka.TOPIC}    ║")
                .log(LoggingLevel.INFO, "║ Partition: ${headers.kafka.PARTITION} Offset: ${headers.kafka.OFFSET}")
                .log(LoggingLevel.INFO, "╚══════════════════════════════════════════════════════════════╝")
                
                // Deserialize JSON to OrderEvent
                .unmarshal().json(JsonLibrary.Jackson, OrderEvent.class)
                
                .log(LoggingLevel.INFO, "Processing event: ${body.eventId} | Type: ${body.eventType}")
                
                // Process the order event
                .process("orderEventProcessor")
                
                .log(LoggingLevel.INFO, "Successfully processed event: ${headers.eventId}")
                
                // end of route
                ;
    }
    
    /**
     * Build the Kafka consumer URI with all configuration options.
     */
    private String buildKafkaUri() {
        return String.format(
                "kafka:%s?" +
                "brokers=%s" +
                "&groupId=%s" +
                "&maxPollRecords=%d" +
                "&autoOffsetReset=earliest" +
                "&autoCommitEnable=false" +          // Manual commit
                "&allowManualCommit=true" +          // Enable manual commit
                "&breakOnFirstError=true" +          // Stop on error for proper handling
                "&sessionTimeoutMs=30000" +
                "&maxPollIntervalMs=600000" +        // 10 minutes
                "&fetchMinBytes=50000" +             // 50KB min per fetch
                "&fetchWaitMaxMs=500" +              // Max 500ms wait
                "&consumersCount=1",                 // Single consumer per route instance
                orderEventsTopic,
                kafkaBootstrapServers,
                consumerGroupId,
                maxPollRecords
        );
    }
}

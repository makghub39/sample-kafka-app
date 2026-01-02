package com.example.route;

import com.example.model.OrderEvent;
import com.example.service.DeadLetterPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * Camel Processor for handling messages that end up in the Dead Letter Queue.
 * Called when OrderEventProcessor fails after all retries.
 */
@Component("deadLetterProcessor")
@Slf4j
@RequiredArgsConstructor
public class DeadLetterProcessor implements Processor {

    private final DeadLetterPublisher deadLetterPublisher;

    @Override
    public void process(Exchange exchange) throws Exception {
        Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        Object originalBody = exchange.getIn().getBody();
        
        log.error("╔══════════════════════════════════════════════════════════════╗");
        log.error("║ DEAD LETTER PROCESSOR                                         ║");
        log.error("║ Message Type: {}                                              ", 
                originalBody != null ? originalBody.getClass().getSimpleName() : "null");
        log.error("║ Exception: {}                                                 ", 
                cause != null ? cause.getMessage() : "Unknown");
        log.error("╚══════════════════════════════════════════════════════════════╝");
        
        // If we have an OrderEvent, send details to DLQ
        if (originalBody instanceof OrderEvent event) {
            log.error("Failed Event ID: {} | Type: {} | Trading Partner: {} | Business Unit: {}", 
                    event.eventId(), event.eventType(), 
                    event.tradingPartnerName(), event.businessUnitName());
            
            // Use existing DeadLetterPublisher infrastructure
            // Could be enhanced to send to Kafka DLQ topic
        }
        
        // Log for monitoring/alerting
        log.error("DLQ: Original body: {}", originalBody);
    }
}

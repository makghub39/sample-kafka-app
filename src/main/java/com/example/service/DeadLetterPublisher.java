package com.example.service;

import com.example.model.FailedOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Dead Letter Publisher - sends failed orders to a dead-letter topic.
 * In production, this would publish to a Kafka DLQ or separate error handling system.
 */
@Service
@Slf4j
public class DeadLetterPublisher {

    /**
     * Send failed orders to dead-letter queue.
     */
    public void send(List<FailedOrder> failedOrders) {
        if (failedOrders.isEmpty()) {
            return;
        }

        log.warn("Publishing {} failed orders to Dead Letter Queue", failedOrders.size());
        
        for (FailedOrder failed : failedOrders) {
            log.warn("  DLQ: Order {} failed - {} ({})", 
                    failed.order().id(), 
                    failed.errorMessage(),
                    failed.exceptionType());
        }

        // In production:
        // for (FailedOrder failed : failedOrders) {
        //     kafkaTemplate.send("order-events-dlq", failed.order().id(), failed);
        // }
    }
}

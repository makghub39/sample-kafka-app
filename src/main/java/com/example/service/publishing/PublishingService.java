package com.example.service.publishing;

import com.example.config.AppMetrics;
import com.example.model.ProcessedOrder;
import com.example.service.WmqPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for publishing processed orders to messaging systems.
 * Supports both grouped and individual publishing strategies.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PublishingService {

    private final WmqPublisher wmqPublisher;
    private final AppMetrics metrics;

    /**
     * Publish processed orders to WMQ.
     * 
     * @param orders Processed orders to publish
     * @param useGrouping If true, applies grouping logic; if false, sends individual messages
     * @param executor Virtual thread executor for parallel publishing
     */
    public void publish(List<ProcessedOrder> orders, boolean useGrouping, ExecutorService executor) {
        if (orders.isEmpty()) {
            log.info("No orders to publish");
            return;
        }

        log.info("Publishing {} orders to WMQ (grouping: {})", orders.size(), useGrouping);
        long startTime = System.currentTimeMillis();

        if (useGrouping) {
            wmqPublisher.sendBatch(orders, executor);
        } else {
            wmqPublisher.sendBatchWithoutGrouping(orders, executor);
        }

        long elapsedTime = System.currentTimeMillis() - startTime;
        metrics.getWmqPublishTimer().record(elapsedTime, TimeUnit.MILLISECONDS);
        log.info("WMQ publish completed in {}ms", elapsedTime);
    }

    /**
     * Publish with grouping enabled.
     */
    public void publishGrouped(List<ProcessedOrder> orders, ExecutorService executor) {
        publish(orders, true, executor);
    }

    /**
     * Publish without grouping (individual messages).
     */
    public void publishIndividual(List<ProcessedOrder> orders, ExecutorService executor) {
        publish(orders, false, executor);
    }
}

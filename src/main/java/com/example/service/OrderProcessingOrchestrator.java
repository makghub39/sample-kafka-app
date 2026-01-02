package com.example.service;

import com.example.config.AppMetrics;
import com.example.model.*;
import com.example.model.PipelineTimingResult.TimingBreakdown;
import com.example.service.cache.CachingDataService;
import com.example.service.preload.DataPreloadService;
import com.example.service.preload.ProcessingContext;
import com.example.service.processing.BusinessLogicService;
import com.example.service.publishing.PublishingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the order processing pipeline.
 * 
 * This class is now a thin orchestrator that delegates to focused services:
 * 1. DataPreloadService   - Batch DB queries (3 parallel)
 * 2. BusinessLogicService - Core processing with concurrency control
 * 3. PublishingService    - WMQ publishing with grouping options
 * 
 * Benefits:
 * - Each service is independently testable
 * - Clear separation of concerns
 * - Easy to modify one stage without affecting others
 * - Services can be reused by other routes/controllers
 */
@Service
@Slf4j
public class OrderProcessingOrchestrator {

    private final DataPreloadService preloadService;
    private final CachingDataService cachingDataService;
    private final BusinessLogicService businessLogicService;
    private final PublishingService publishingService;
    private final AppMetrics metrics;
    private final ExecutorService executor;

    public OrderProcessingOrchestrator(
            DataPreloadService preloadService,
            CachingDataService cachingDataService,
            BusinessLogicService businessLogicService,
            PublishingService publishingService,
            AppMetrics metrics,
            @Qualifier("unlimitedVirtualExecutor") ExecutorService executor) {
        this.preloadService = preloadService;
        this.cachingDataService = cachingDataService;
        this.businessLogicService = businessLogicService;
        this.publishingService = publishingService;
        this.metrics = metrics;
        this.executor = executor;
    }

    @Value("${app.cache.data.enabled:true}")
    private boolean dataCacheEnabled;

    /**
     * Process orders with default (no grouping).
     */
    public ProcessingResult processOrders(List<Order> orders) {
        return processOrders(orders, false);
    }

    /**
     * Process orders and return detailed timing breakdown.
     * 
     * @param orders List of orders to process
     * @param useGrouping If true, applies grouping before WMQ publish
     * @return ProcessingResultWithTiming with individual stage timings
     */
    public ProcessingResultWithTiming processOrdersWithTiming(List<Order> orders, boolean useGrouping) {
        if (orders.isEmpty()) {
            return new ProcessingResultWithTiming(List.of(), List.of(), 0, 0, 0, 0);
        }

        long startTime = System.currentTimeMillis();
        List<String> orderIds = orders.stream().map(Order::id).toList();

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("PIPELINE START: {} orders | Grouping: {}", orders.size(), useGrouping ? "ENABLED" : "DISABLED");
        log.info("═══════════════════════════════════════════════════════════════");

        long preloadTime = 0;
        long processingTime = 0;
        long publishTime = 0;

        // STAGE 1: Preload data from DB
        log.info("STAGE 1: Data Preload (cache={})", dataCacheEnabled ? "ON" : "OFF");
        long preloadStart = System.currentTimeMillis();
        
        ProcessingContext context = dataCacheEnabled 
                ? cachingDataService.preloadData(orderIds, executor)
                : preloadService.preloadData(orderIds, executor);
        
        preloadTime = System.currentTimeMillis() - preloadStart;

        // STAGE 2: Business Logic Processing
        log.info("STAGE 2: Business Logic Processing");
        long processingStart = System.currentTimeMillis();
        
        var output = businessLogicService.processOrders(orders, context, executor);
        
        processingTime = System.currentTimeMillis() - processingStart;
        metrics.getProcessingTimer().record(processingTime, TimeUnit.MILLISECONDS);

        // STAGE 3: Publish to WMQ
        log.info("STAGE 3: WMQ Publishing");
        long publishStart = System.currentTimeMillis();
        
        publishingService.publish(output.successes(), useGrouping, executor);
        
        publishTime = System.currentTimeMillis() - publishStart;

        // SUMMARY
        long totalTime = System.currentTimeMillis() - startTime;
        metrics.getTotalProcessingTimer().record(totalTime, TimeUnit.MILLISECONDS);
        metrics.incrementOrdersProcessed(output.successes().size());
        metrics.incrementOrdersFailed(output.failures().size());

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("PIPELINE COMPLETE | Total: {}ms", totalTime);
        log.info("  Preload: {}ms | Process: {}ms | Publish: {}ms", preloadTime, processingTime, publishTime);
        log.info("  Successes: {} | Failures: {}", output.successes().size(), output.failures().size());
        log.info("═══════════════════════════════════════════════════════════════");

        return new ProcessingResultWithTiming(
                output.successes(),
                output.failures(),
                totalTime,
                preloadTime,
                processingTime,
                publishTime
        );
    }

    /**
     * Main processing pipeline - orchestrates all stages.
     * 
     * @param orders List of orders to process
     * @param useGrouping If true, applies grouping before WMQ publish
     * @return ProcessingResult with successes, failures, and timing
     */
    public ProcessingResult processOrders(List<Order> orders, boolean useGrouping) {
        return processOrdersWithTiming(orders, useGrouping).toProcessingResult();
    }
}

package com.example.controller;

import com.example.config.AppMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST endpoint for application metrics summary.
 * Provides a single endpoint with all key metrics.
 * 
 * GET /api/metrics/summary
 */
@RestController
@RequestMapping("/api/metrics")
@RequiredArgsConstructor
public class MetricsController {

    private final AppMetrics appMetrics;

    /**
     * Get all metrics in a single response.
     */
    @GetMapping("/summary")
    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> response = new LinkedHashMap<>();
        
        response.put("timestamp", Instant.now().toString());
        response.put("orders", getOrderMetrics());
        response.put("timing", getTimingMetrics());
        response.put("wmq", getWmqMetrics());
        
        return response;
    }

    /**
     * Get order count metrics only.
     */
    @GetMapping("/orders")
    public Map<String, Object> getOrderMetrics() {
        Map<String, Object> orders = new LinkedHashMap<>();
        
        double total = appMetrics.getOrdersTotalCounter().count();
        double success = appMetrics.getOrdersSuccessCounter().count();
        double failed = appMetrics.getOrdersFailedCounter().count();
        double events = appMetrics.getEventsReceivedCounter().count();
        
        orders.put("total", (long) total);
        orders.put("success", (long) success);
        orders.put("failed", (long) failed);
        orders.put("eventsReceived", (long) events);
        
        if (total > 0) {
            orders.put("successRate", String.format("%.2f%%", (success / total) * 100));
        } else {
            orders.put("successRate", "N/A");
        }
        
        return orders;
    }

    /**
     * Get timing metrics only.
     */
    @GetMapping("/timing")
    public Map<String, Object> getTimingMetrics() {
        Map<String, Object> timing = new LinkedHashMap<>();
        
        timing.put("totalEventProcessing", getTimerStats(appMetrics.getTotalEventTimer()));
        timing.put("mongoDbFetch", getTimerStats(appMetrics.getMongoDbFetchTimer()));
        timing.put("h2DbFetch", getTimerStats(appMetrics.getDbFetchTimer()));
        timing.put("orderProcessing", getTimerStats(appMetrics.getProcessingTimer()));
        timing.put("wmqPublish", getTimerStats(appMetrics.getWmqPublishTimer()));
        
        return timing;
    }

    /**
     * Get WMQ metrics only.
     */
    @GetMapping("/wmq")
    public Map<String, Object> getWmqMetrics() {
        Map<String, Object> wmq = new LinkedHashMap<>();
        
        wmq.put("messagesSent", (long) appMetrics.getWmqMessagesCounter().count());
        wmq.put("publishTiming", getTimerStats(appMetrics.getWmqPublishTimer()));
        
        return wmq;
    }

    /**
     * Extract stats from a Timer.
     */
    private Map<String, Object> getTimerStats(Timer timer) {
        Map<String, Object> stats = new LinkedHashMap<>();
        
        long count = timer.count();
        stats.put("count", count);
        
        if (count > 0) {
            stats.put("totalTimeMs", String.format("%.2f", timer.totalTime(TimeUnit.MILLISECONDS)));
            stats.put("avgTimeMs", String.format("%.2f", timer.mean(TimeUnit.MILLISECONDS)));
            stats.put("maxTimeMs", String.format("%.2f", timer.max(TimeUnit.MILLISECONDS)));
        } else {
            stats.put("totalTimeMs", "0.00");
            stats.put("avgTimeMs", "N/A");
            stats.put("maxTimeMs", "N/A");
        }
        
        return stats;
    }
}

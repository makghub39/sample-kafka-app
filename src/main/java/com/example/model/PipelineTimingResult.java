package com.example.model;

import java.util.List;
import java.util.Map;

/**
 * Detailed timing breakdown for the order processing pipeline.
 * 
 * Captures time spent in each stage:
 * - MongoDB fetch (when applicable)
 * - DB preload (H2 batch lookups)
 * - Core business logic processing
 * - JMS/WMQ publishing
 */
public record PipelineTimingResult(
        List<ProcessedOrder> successes,
        List<FailedOrder> failures,
        long totalTimeMs,
        TimingBreakdown timing
) {
    
    /**
     * Detailed timing breakdown for each pipeline stage.
     */
    public record TimingBreakdown(
            long mongoFetchMs,
            long dbPreloadMs,
            long businessLogicMs,
            long jmsPublishMs,
            long totalPipelineMs
    ) {
        /**
         * Create breakdown with percentages.
         */
        public Map<String, Object> toDetailedMap() {
            long total = totalPipelineMs > 0 ? totalPipelineMs : 1;
            return Map.of(
                    "mongoFetchMs", mongoFetchMs,
                    "mongoFetchPercent", String.format("%.1f%%", (mongoFetchMs * 100.0) / total),
                    "dbPreloadMs", dbPreloadMs,
                    "dbPreloadPercent", String.format("%.1f%%", (dbPreloadMs * 100.0) / total),
                    "businessLogicMs", businessLogicMs,
                    "businessLogicPercent", String.format("%.1f%%", (businessLogicMs * 100.0) / total),
                    "jmsPublishMs", jmsPublishMs,
                    "jmsPublishPercent", String.format("%.1f%%", (jmsPublishMs * 100.0) / total),
                    "totalPipelineMs", totalPipelineMs
            );
        }

        /**
         * Create from individual timings (no MongoDB).
         */
        public static TimingBreakdown fromPipeline(long dbPreloadMs, long businessLogicMs, long jmsPublishMs) {
            return new TimingBreakdown(0, dbPreloadMs, businessLogicMs, jmsPublishMs, 
                    dbPreloadMs + businessLogicMs + jmsPublishMs);
        }

        /**
         * Create with MongoDB timing included.
         */
        public static TimingBreakdown withMongoFetch(long mongoFetchMs, long dbPreloadMs, 
                                                      long businessLogicMs, long jmsPublishMs) {
            return new TimingBreakdown(mongoFetchMs, dbPreloadMs, businessLogicMs, jmsPublishMs,
                    mongoFetchMs + dbPreloadMs + businessLogicMs + jmsPublishMs);
        }
    }

    /**
     * Convenience factory for creating result from ProcessingResultWithTiming.
     */
    public static PipelineTimingResult from(ProcessingResultWithTiming result, long mongoFetchMs) {
        return new PipelineTimingResult(
                result.successes(),
                result.failures(),
                result.totalTimeMs(),
                TimingBreakdown.withMongoFetch(
                        mongoFetchMs,
                        result.preloadTimeMs(),
                        result.processingTimeMs(),
                        result.publishTimeMs()
                )
        );
    }
}

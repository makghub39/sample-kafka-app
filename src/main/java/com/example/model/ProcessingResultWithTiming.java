package com.example.model;

import java.util.List;

/**
 * Processing result with detailed timing breakdown for each pipeline stage.
 */
public record ProcessingResultWithTiming(
        List<ProcessedOrder> successes,
        List<FailedOrder> failures,
        long totalTimeMs,
        long preloadTimeMs,
        long processingTimeMs,
        long publishTimeMs
) {
    /**
     * Convert to standard ProcessingResult (without timing details).
     */
    public ProcessingResult toProcessingResult() {
        return new ProcessingResult(successes, failures, totalTimeMs);
    }
}

package com.example.model;

import java.util.List;

/**
 * Result of batch processing with success/failure tracking.
 */
public record ProcessingResult(
    List<ProcessedOrder> successes,
    List<FailedOrder> failures,
    long processingTimeMs
) {}

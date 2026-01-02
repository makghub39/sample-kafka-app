package com.example.model;

/**
 * Failed order for dead-letter queue.
 */
public record FailedOrder(
    Order order,
    String errorMessage,
    String exceptionType
) {}

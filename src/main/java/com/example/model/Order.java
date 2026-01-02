package com.example.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Order entity from API/database.
 */
public record Order(
    String id,
    String customerId,
    String status,
    BigDecimal amount,
    LocalDateTime createdAt
) {}

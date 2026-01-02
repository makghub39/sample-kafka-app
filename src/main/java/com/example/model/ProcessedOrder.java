package com.example.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Final processed order to be sent to WMQ.
 */
public record ProcessedOrder(
    String orderId,
    String customerId,
    String customerName,
    String customerTier,
    BigDecimal finalPrice,
    String warehouseLocation,
    String status,
    LocalDateTime processedAt,
    String processedBy
) {}

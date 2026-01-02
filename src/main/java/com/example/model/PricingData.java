package com.example.model;

import java.math.BigDecimal;

/**
 * Pricing data fetched from database.
 */
public record PricingData(
    String orderId,
    BigDecimal basePrice,
    BigDecimal discount,
    BigDecimal taxRate
) {}

package com.example.model;

/**
 * Customer data fetched from database.
 */
public record CustomerData(
    String customerId,
    String name,
    String email,
    String tier
) {}

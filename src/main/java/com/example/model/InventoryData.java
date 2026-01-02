package com.example.model;

/**
 * Inventory data fetched from database.
 */
public record InventoryData(
    String orderId,
    String sku,
    int quantityAvailable,
    String warehouseLocation
) {}

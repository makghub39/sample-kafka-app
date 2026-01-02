package com.example.service.preload;

import com.example.model.CustomerData;
import com.example.model.InventoryData;
import com.example.model.PricingData;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Holds all preloaded data for order processing.
 * Passed between processing stages to avoid repeated DB calls.
 */
@Getter
@Builder
public class ProcessingContext {
    
    private final Map<String, CustomerData> customerData;
    private final Map<String, InventoryData> inventoryData;
    private final Map<String, PricingData> pricingData;
    
    /**
     * Get customer data for a specific order.
     */
    public CustomerData getCustomer(String orderId) {
        return customerData != null ? customerData.get(orderId) : null;
    }
    
    /**
     * Get inventory data for a specific order.
     */
    public InventoryData getInventory(String orderId) {
        return inventoryData != null ? inventoryData.get(orderId) : null;
    }
    
    /**
     * Get pricing data for a specific order.
     */
    public PricingData getPricing(String orderId) {
        return pricingData != null ? pricingData.get(orderId) : null;
    }
    
    /**
     * Check if context has all required data.
     */
    public boolean isComplete() {
        return customerData != null && inventoryData != null && pricingData != null;
    }
}

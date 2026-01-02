package com.example.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Grouped orders record - multiple orders combined into one WMQ message.
 * Used when business logic requires batching orders together 
 * (e.g., same customer, same warehouse, same shipment, etc.)
 */
public record GroupedOrderMessage(
    String groupId,
    String groupingKey,        // e.g., "CUST-001|WAREHOUSE-A" or "SHIPMENT-123"
    String groupType,          // e.g., "CUSTOMER", "WAREHOUSE", "SHIPMENT", "BATCH"
    List<ProcessedOrder> orders,
    int orderCount,
    BigDecimal totalAmount,
    LocalDateTime groupedAt,
    String groupedBy
) {
    /**
     * Create a grouped message from a list of orders.
     */
    public static GroupedOrderMessage create(
            String groupingKey, 
            String groupType, 
            List<ProcessedOrder> orders) {
        
        BigDecimal total = orders.stream()
                .map(ProcessedOrder::finalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        String groupId = "GRP-" + System.currentTimeMillis() + "-" + groupingKey.hashCode();
        
        return new GroupedOrderMessage(
                groupId,
                groupingKey,
                groupType,
                orders,
                orders.size(),
                total,
                LocalDateTime.now(),
                "kafka-order-processor"
        );
    }
}

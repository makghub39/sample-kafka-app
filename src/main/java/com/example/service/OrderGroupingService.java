package com.example.service;

import com.example.model.GroupedOrderMessage;
import com.example.model.ProcessedOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business logic for grouping orders before sending to WMQ.
 * 
 * Grouping strategies:
 * 1. BY_CUSTOMER - Group all orders from same customer into one message
 * 2. BY_WAREHOUSE - Group orders shipping from same warehouse
 * 3. BY_TIER - Group by customer tier (GOLD, SILVER, BRONZE)
 * 4. HIGH_VALUE - Group orders above threshold, send others individually
 * 5. CUSTOM - Implement your own grouping logic
 */
@Service
@Slf4j
public class OrderGroupingService {

    @Value("${app.grouping.strategy:BY_CUSTOMER}")
    private String groupingStrategy;

    @Value("${app.grouping.high-value-threshold:1000}")
    private BigDecimal highValueThreshold;

    @Value("${app.grouping.min-group-size:2}")
    private int minGroupSize;

    /**
     * Result of grouping: grouped orders + individual orders
     */
    public record GroupingResult(
            List<GroupedOrderMessage> groupedMessages,
            List<ProcessedOrder> individualOrders
    ) {
        public int totalGroupedOrders() {
            return groupedMessages.stream()
                    .mapToInt(GroupedOrderMessage::orderCount)
                    .sum();
        }
    }

    /**
     * Main grouping method - applies business logic to decide what gets grouped.
     */
    public GroupingResult groupOrders(List<ProcessedOrder> orders) {
        if (orders.isEmpty()) {
            return new GroupingResult(List.of(), List.of());
        }

        log.info("Grouping {} orders using strategy: {}", orders.size(), groupingStrategy);

        return switch (groupingStrategy.toUpperCase()) {
            case "BY_CUSTOMER" -> groupByCustomer(orders);
            case "BY_WAREHOUSE" -> groupByWarehouse(orders);
            case "BY_TIER" -> groupByCustomerTier(orders);
            case "HIGH_VALUE" -> groupHighValueOrders(orders);
            case "NONE" -> noGrouping(orders);
            default -> groupByCustomer(orders); // default strategy
        };
    }

    /**
     * Strategy: Group orders by customer ID.
     * Same customer's orders go into one WMQ message.
     */
    private GroupingResult groupByCustomer(List<ProcessedOrder> orders) {
        Map<String, List<ProcessedOrder>> byCustomer = orders.stream()
                .collect(Collectors.groupingBy(ProcessedOrder::customerId));

        return buildGroupingResult(byCustomer, "CUSTOMER");
    }

    /**
     * Strategy: Group orders by warehouse location.
     * Orders from same warehouse go into one message (for batch shipping).
     */
    private GroupingResult groupByWarehouse(List<ProcessedOrder> orders) {
        Map<String, List<ProcessedOrder>> byWarehouse = orders.stream()
                .collect(Collectors.groupingBy(
                        o -> o.warehouseLocation() != null ? o.warehouseLocation() : "UNKNOWN"
                ));

        return buildGroupingResult(byWarehouse, "WAREHOUSE");
    }

    /**
     * Strategy: Group orders by customer tier.
     * Process GOLD tier together, SILVER together, etc.
     */
    private GroupingResult groupByCustomerTier(List<ProcessedOrder> orders) {
        Map<String, List<ProcessedOrder>> byTier = orders.stream()
                .collect(Collectors.groupingBy(
                        o -> o.customerTier() != null ? o.customerTier() : "STANDARD"
                ));

        return buildGroupingResult(byTier, "TIER");
    }

    /**
     * Strategy: Group high-value orders together, send low-value individually.
     * Useful for priority processing or fraud review.
     */
    private GroupingResult groupHighValueOrders(List<ProcessedOrder> orders) {
        // Partition into high-value and regular orders
        Map<Boolean, List<ProcessedOrder>> partitioned = orders.stream()
                .collect(Collectors.partitioningBy(
                        o -> o.finalPrice() != null && 
                             o.finalPrice().compareTo(highValueThreshold) >= 0
                ));

        List<ProcessedOrder> highValue = partitioned.get(true);
        List<ProcessedOrder> regular = partitioned.get(false);

        List<GroupedOrderMessage> grouped = new ArrayList<>();
        List<ProcessedOrder> individual = new ArrayList<>();

        // High-value orders get grouped together
        if (highValue.size() >= minGroupSize) {
            grouped.add(GroupedOrderMessage.create("HIGH_VALUE", "HIGH_VALUE", highValue));
            log.info("Grouped {} high-value orders (>= ${})", highValue.size(), highValueThreshold);
        } else {
            individual.addAll(highValue);
        }

        // Regular orders sent individually
        individual.addAll(regular);

        return new GroupingResult(grouped, individual);
    }

    /**
     * Strategy: No grouping - all orders sent individually.
     */
    private GroupingResult noGrouping(List<ProcessedOrder> orders) {
        return new GroupingResult(List.of(), orders);
    }

    /**
     * Build GroupingResult from a grouped map.
     * Groups with >= minGroupSize become grouped messages,
     * smaller groups are sent individually.
     */
    private GroupingResult buildGroupingResult(
            Map<String, List<ProcessedOrder>> groupedMap, 
            String groupType) {
        
        List<GroupedOrderMessage> groupedMessages = new ArrayList<>();
        List<ProcessedOrder> individualOrders = new ArrayList<>();

        for (Map.Entry<String, List<ProcessedOrder>> entry : groupedMap.entrySet()) {
            String key = entry.getKey();
            List<ProcessedOrder> groupOrders = entry.getValue();

            if (groupOrders.size() >= minGroupSize) {
                // Create grouped message
                groupedMessages.add(GroupedOrderMessage.create(key, groupType, groupOrders));
                log.debug("Created group '{}' with {} orders", key, groupOrders.size());
            } else {
                // Too small to group - send individually
                individualOrders.addAll(groupOrders);
            }
        }

        log.info("Grouping complete: {} grouped messages ({} orders), {} individual orders",
                groupedMessages.size(),
                groupedMessages.stream().mapToInt(GroupedOrderMessage::orderCount).sum(),
                individualOrders.size());

        return new GroupingResult(groupedMessages, individualOrders);
    }
}

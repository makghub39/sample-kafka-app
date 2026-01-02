package com.example.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Set;

/**
 * Event received from Kafka topic.
 * 
 * Contains trading partner and business unit info used to query orders from MongoDB.
 * Does NOT contain order IDs - orders are fetched based on these criteria.
 */
public record OrderEvent(
    String eventId,
    String eventType,
    String tradingPartnerName,
    String businessUnitName
) {
    /**
     * Event types that require grouped WMQ publishing.
     * Orders from these events are grouped by business rules before sending to WMQ.
     */
    private static final Set<String> GROUPED_EVENT_TYPES = Set.of(
            "BULK_ORDER",
            "BATCH_SHIPMENT",
            "CONSOLIDATE_ORDERS",
            "WAREHOUSE_BATCH"
    );

    /**
     * Event types that require individual WMQ publishing.
     * Each order is sent as a separate WMQ message.
     */
    private static final Set<String> INDIVIDUAL_EVENT_TYPES = Set.of(
            "SINGLE_ORDER",
            "EXPRESS_ORDER",
            "PRIORITY_ORDER",
            "PROCESS_ORDERS"  // default type
    );

    /**
     * Determines if this event's orders should be grouped for WMQ publishing.
     */
    @JsonIgnore
    public boolean requiresGrouping() {
        return eventType != null && GROUPED_EVENT_TYPES.contains(eventType.toUpperCase());
    }

    /**
     * Check if event type is known/valid.
     */
    @JsonIgnore
    public boolean isValidEventType() {
        if (eventType == null) return false;
        String upper = eventType.toUpperCase();
        return GROUPED_EVENT_TYPES.contains(upper) || INDIVIDUAL_EVENT_TYPES.contains(upper);
    }
}

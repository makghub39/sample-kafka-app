package com.example.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * MongoDB document representing an order.
 * This replaces the API call to fetch orders based on Kafka event.
 * 
 * Flow: Kafka Event → MongoDB Query (this) → H2 Batch Lookups → WMQ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "orders")
public class OrderDocument {

    @Id
    private String id;

    @Indexed(unique = true)
    private String orderId;

    @Indexed
    private String customerId;

    @Indexed
    private String tradingPartnerName;

    @Indexed
    private String businessUnitName;

    @Indexed
    private String status;

    private BigDecimal amount;

    private Instant createdAt;

    private List<OrderItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItem {
        private String sku;
        private int quantity;
        private BigDecimal price;
    }

    /**
     * Convert MongoDB document to Order record used in processing.
     * Maps to existing Order model structure.
     */
    public Order toOrder() {
        return new Order(
                this.orderId,      // id
                this.customerId,   // customerId
                this.status,       // status
                this.amount,       // amount
                this.createdAt != null 
                    ? java.time.LocalDateTime.ofInstant(this.createdAt, java.time.ZoneId.systemDefault())
                    : java.time.LocalDateTime.now()  // createdAt
        );
    }
}

package com.example.repository;

import com.example.model.OrderDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB repository for fetching orders.
 * This replaces the external API call in the original design.
 * 
 * Original: Kafka Event → API Call (HTTP, slow) → List<Order>
 * Optimized: Kafka Event → MongoDB Query (fast) → List<Order>
 */
@Repository
public interface MongoOrderRepository extends MongoRepository<OrderDocument, String> {

    /**
     * Find order by orderId field (not MongoDB _id)
     */
    Optional<OrderDocument> findByOrderId(String orderId);

    /**
     * Find multiple orders by their orderIds - used for batch processing
     */
    List<OrderDocument> findByOrderIdIn(List<String> orderIds);

    /**
     * Find orders by trading partner and business unit (primary query method)
     */
    List<OrderDocument> findByTradingPartnerNameAndBusinessUnitNameAndStatus(
            String tradingPartnerName, String businessUnitName, String status);

    /**
     * Find orders by trading partner only
     */
    List<OrderDocument> findByTradingPartnerNameAndStatus(String tradingPartnerName, String status);

    /**
     * Find orders by business unit only
     */
    List<OrderDocument> findByBusinessUnitNameAndStatus(String businessUnitName, String status);

    /**
     * Find all orders for a specific customer
     */
    List<OrderDocument> findByCustomerId(String customerId);

    /**
     * Find orders by status
     */
    List<OrderDocument> findByStatus(String status);

    /**
     * Find orders with amount greater than threshold
     */
    @Query("{ 'amount': { $gt: ?0 } }")
    List<OrderDocument> findHighValueOrders(double minAmount);

    /**
     * Find pending orders limited by count (for batch processing)
     */
    List<OrderDocument> findTop100ByStatusOrderByCreatedAtAsc(String status);

    /**
     * Count orders by status
     */
    long countByStatus(String status);

    /**
     * Count orders by trading partner name
     */
    long countByTradingPartnerName(String tradingPartnerName);

    /**
     * Count orders by trading partner and business unit
     */
    long countByTradingPartnerNameAndBusinessUnitName(String tradingPartnerName, String businessUnitName);
}

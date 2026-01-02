package com.example.repository;

import com.example.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Repository with batch DB operations.
 * Uses IN clause to fetch data for multiple orders in a single query.
 * 
 * IMPORTANT: Built-in chunking protects against SQL Server's 2100 parameter limit.
 * All batch methods automatically chunk large ID lists.
 */
@Repository
@Slf4j
public class OrderRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    
    /**
     * Maximum IDs per query chunk.
     * SQL Server has a 2100 parameter limit - we use 500 as a safe default.
     * This can be configured via app.db.chunk-size property.
     */
    private final int chunkSize;
    
    private final int maxRetries;
    private final long retryDelayMs;
    private final SqlTemplateLoader sqlLoader;

        public OrderRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            @Value("${app.db.chunk-size:500}") int chunkSize,
            @Value("${app.db.max-retries:2}") int maxRetries,
            @Value("${app.db.retry-delay-ms:100}") long retryDelayMs,
            SqlTemplateLoader sqlLoader) {
        this.jdbcTemplate = jdbcTemplate;
        this.chunkSize = chunkSize;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.sqlLoader = sqlLoader;
        log.info("OrderRepository initialized with chunk size: {}, maxRetries: {}, retryDelayMs: {}ms",
            chunkSize, maxRetries, retryDelayMs);
        }

    /**
     * Fetch orders by IDs - simulates API call that returns orders.
     * Automatically chunks large ID lists to avoid SQL parameter limits.
     */
    public List<Order> findOrdersByIds(List<String> orderIds) {
        if (orderIds.isEmpty()) return List.of();

        // Chunk if needed
        if (orderIds.size() > chunkSize) {
            List<List<String>> parts = partition(orderIds, chunkSize);
            log.info("findOrdersByIds: total chunks = {}, chunkSize = {}", parts.size(), chunkSize);
            List<Order> result = new ArrayList<>();
            int chunkNum = 1;
            for (List<String> chunk : parts) {
                log.info("findOrdersByIds: processing chunk {}/{} ({} ids)", chunkNum, parts.size(), chunk.size());
                result.addAll(findOrdersByIdsInternal(chunk));
                chunkNum++;
            }
            return result;
        }
        
        return findOrdersByIdsInternal(orderIds);
    }

    private List<Order> findOrdersByIdsInternal(List<String> orderIds) {
        String sql = sqlLoader.load("findOrdersByIds");

        MapSqlParameterSource params = new MapSqlParameterSource("orderIds", orderIds);

        return jdbcTemplate.query(sql, params, (rs, rowNum) -> new Order(
                rs.getString("order_id"),
                rs.getString("customer_id"),
                rs.getString("status"),
                rs.getBigDecimal("amount"),
                rs.getTimestamp("created_at").toLocalDateTime()
        ));
    }

    /**
     * Batch fetch customer data for multiple orders.
     * Single query instead of N queries - KEY OPTIMIZATION!
     * Automatically chunks large ID lists to avoid SQL parameter limits.
     */
    public Map<String, CustomerData> batchFetchCustomerData(List<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();

        log.debug("Batch fetching customer data for {} orders", orderIds.size());

        // Chunk if needed to avoid SQL Server's 2100 parameter limit
        if (orderIds.size() > chunkSize) {
            List<List<String>> parts = partition(orderIds, chunkSize);
            log.info("batchFetchCustomerData: total chunks = {}, chunkSize = {}", parts.size(), chunkSize);
            Map<String, CustomerData> result = new HashMap<>();
            int chunkNum = 1;
            for (List<String> chunk : parts) {
                log.info("batchFetchCustomerData: processing chunk {}/{} ({} ids)", chunkNum, parts.size(), chunk.size());
                try {
                    result.putAll(batchFetchCustomerDataInternal(chunk));
                } catch (DataAccessException e) {
                    log.error("batchFetchCustomerData: chunk {}/{} failed after retries, continuing with remaining chunks. chunkSize={} error={}", chunkNum, parts.size(), chunk.size(), e.getMessage());
                }
                chunkNum++;
            }
            return result;
        }

        return batchFetchCustomerDataInternal(orderIds);
    }

    private Map<String, CustomerData> batchFetchCustomerDataInternal(List<String> orderIds) {
        return withRetry("batchFetchCustomerData", () -> {
            String sql = sqlLoader.load("batchFetchCustomerData");

            MapSqlParameterSource params = new MapSqlParameterSource("orderIds", orderIds);

            return jdbcTemplate.query(sql, params, rs -> {
                Map<String, CustomerData> result = new HashMap<>();
                while (rs.next()) {
                    result.put(
                            rs.getString("order_id"),
                            new CustomerData(
                                    rs.getString("customer_id"),
                                    rs.getString("name"),
                                    rs.getString("email"),
                                    rs.getString("tier")
                            )
                    );
                }
                return result;
            });
        });
    }

    /**
     * Batch fetch inventory data for multiple orders.
     * Automatically chunks large ID lists to avoid SQL parameter limits.
     */
    public Map<String, InventoryData> batchFetchInventoryData(List<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();

        log.debug("Batch fetching inventory data for {} orders", orderIds.size());

        // Chunk if needed to avoid SQL Server's 2100 parameter limit
        if (orderIds.size() > chunkSize) {
            List<List<String>> parts = partition(orderIds, chunkSize);
            log.info("batchFetchInventoryData: total chunks = {}, chunkSize = {}", parts.size(), chunkSize);
            Map<String, InventoryData> result = new HashMap<>();
            int chunkNum = 1;
            for (List<String> chunk : parts) {
                log.info("batchFetchInventoryData: processing chunk {}/{} ({} ids)", chunkNum, parts.size(), chunk.size());
                try {
                    result.putAll(batchFetchInventoryDataInternal(chunk));
                } catch (DataAccessException e) {
                    log.error("batchFetchInventoryData: chunk {}/{} failed after retries, continuing with remaining chunks. chunkSize={} error={}", chunkNum, parts.size(), chunk.size(), e.getMessage());
                }
                chunkNum++;
            }
            return result;
        }

        return batchFetchInventoryDataInternal(orderIds);
    }

    private Map<String, InventoryData> batchFetchInventoryDataInternal(List<String> orderIds) {
        return withRetry("batchFetchInventoryData", () -> {
            String sql = sqlLoader.load("batchFetchInventoryData");

            MapSqlParameterSource params = new MapSqlParameterSource("orderIds", orderIds);

            return jdbcTemplate.query(sql, params, rs -> {
                Map<String, InventoryData> result = new HashMap<>();
                while (rs.next()) {
                    result.put(
                            rs.getString("order_id"),
                            new InventoryData(
                                    rs.getString("order_id"),
                                    rs.getString("sku"),
                                    rs.getInt("quantity_available"),
                                    rs.getString("warehouse_location")
                            )
                    );
                }
                return result;
            });
        });
    }

    /**
     * Batch fetch pricing data for multiple orders.
     * Automatically chunks large ID lists to avoid SQL parameter limits.
     */
    public Map<String, PricingData> batchFetchPricingData(List<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();

        log.debug("Batch fetching pricing data for {} orders", orderIds.size());

        // Chunk if needed to avoid SQL Server's 2100 parameter limit
        if (orderIds.size() > chunkSize) {
            List<List<String>> parts = partition(orderIds, chunkSize);
            log.info("batchFetchPricingData: total chunks = {}, chunkSize = {}", parts.size(), chunkSize);
            Map<String, PricingData> result = new HashMap<>();
            int chunkNum = 1;
            for (List<String> chunk : parts) {
                log.info("batchFetchPricingData: processing chunk {}/{} ({} ids)", chunkNum, parts.size(), chunk.size());
                try {
                    result.putAll(batchFetchPricingDataInternal(chunk));
                } catch (DataAccessException e) {
                    log.error("batchFetchPricingData: chunk {}/{} failed after retries, continuing with remaining chunks. chunkSize={} error={}", chunkNum, parts.size(), chunk.size(), e.getMessage());
                }
                chunkNum++;
            }
            return result;
        }

        return batchFetchPricingDataInternal(orderIds);
    }

    private Map<String, PricingData> batchFetchPricingDataInternal(List<String> orderIds) {
        return withRetry("batchFetchPricingData", () -> {
            String sql = sqlLoader.load("batchFetchPricingData");

            MapSqlParameterSource params = new MapSqlParameterSource("orderIds", orderIds);

            return jdbcTemplate.query(sql, params, rs -> {
                Map<String, PricingData> result = new HashMap<>();
                while (rs.next()) {
                    result.put(
                            rs.getString("order_id"),
                            new PricingData(
                                    rs.getString("order_id"),
                                    rs.getBigDecimal("base_price"),
                                    rs.getBigDecimal("discount"),
                                    rs.getBigDecimal("tax_rate")
                            )
                    );
                }
                return result;
            });
        });
    }

    /**
     * Partition a list into chunks of specified size.
     */
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    // ═══════════════════════════════════════════════════════════════
    // TRADING PARTNER & BUSINESS UNIT STATUS LOOKUPS
    // Single queries used for pre-validation (cached at service layer)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Fetch trading partner status by name.
     * Returns Optional.empty() if partner not found.
     */
    public Optional<TradingPartnerStatus> findTradingPartnerByName(String partnerName) {
        log.debug("Looking up trading partner status: {}", partnerName);
        
        String sql = sqlLoader.load("findTradingPartnerByName");
        
        MapSqlParameterSource params = new MapSqlParameterSource("partnerName", partnerName);
        
        try {
            return Optional.ofNullable(
                jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new TradingPartnerStatus(
                    rs.getString("partner_id"),
                    rs.getString("partner_name"),
                    rs.getString("status"),
                    rs.getTimestamp("updated_at") != null 
                        ? rs.getTimestamp("updated_at").toLocalDateTime() 
                        : null
                ))
            );
        } catch (EmptyResultDataAccessException e) {
            log.warn("Trading partner not found: {}", partnerName);
            return Optional.empty();
        }
    }

    /**
     * Fetch business unit status by name.
     * Returns Optional.empty() if unit not found.
     */
    public Optional<BusinessUnitStatus> findBusinessUnitByName(String unitName) {
        log.debug("Looking up business unit status: {}", unitName);
        
        String sql = sqlLoader.load("findBusinessUnitByName");
        
        MapSqlParameterSource params = new MapSqlParameterSource("unitName", unitName);
        
        try {
            return Optional.ofNullable(
                jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new BusinessUnitStatus(
                    rs.getString("unit_id"),
                    rs.getString("unit_name"),
                    rs.getString("status"),
                    rs.getTimestamp("updated_at") != null 
                        ? rs.getTimestamp("updated_at").toLocalDateTime() 
                        : null
                ))
            );
        } catch (EmptyResultDataAccessException e) {
            log.warn("Business unit not found: {}", unitName);
            return Optional.empty();
        }
    }



    /**
     * Retry wrapper for transient DB failures.
     * Retries up to `maxRetries` times (configurable) with exponential backoff and jitter.
     */
    private <T> T withRetry(String operationName, Supplier<T> operation) {
        int attempt = 0;
        final int totalAttempts = maxRetries + 1;
        while (true) {
            try {
                attempt++;
                return operation.get();
            } catch (DataAccessException e) {
                if (attempt > maxRetries) {
                    log.error("Operation '{}' failed after {} attempts: {}",
                            operationName, attempt, e.getMessage());
                    throw e;
                }

                // exponential backoff: base * 2^(attempt-1)
                long baseDelay = retryDelayMs * (1L << (attempt - 1));
                long jitter = ThreadLocalRandom.current().nextLong(0, Math.min(1000L, baseDelay));
                long delay = Math.min(baseDelay + jitter, 60000L); // cap at 60s

                log.warn("Operation '{}' failed (attempt {}/{}), retrying in {}ms: {}",
                        operationName, attempt, totalAttempts, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}

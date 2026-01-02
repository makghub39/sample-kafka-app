package com.example.service;

import com.example.model.GroupedOrderMessage;
import com.example.model.ProcessedOrder;
import com.example.service.OrderGroupingService.GroupingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IBM MQ Publisher - sends processed orders to WebSphere MQ.
 * Supports:
 * - Grouped orders (multiple orders in one message based on business logic)
 * - Individual orders (single order per message)
 * - Parallel publishing with virtual threads (concurrency limited)
 */
@Service
@Slf4j
public class WmqPublisher {

    @Autowired(required = false)
    private JmsTemplate jmsTemplate;

    @Autowired
    private OrderGroupingService orderGroupingService;

    @Value("${app.wmq.enabled:false}")
    private boolean wmqEnabled;

    @Value("${app.wmq.queue-name:DEV.QUEUE.1}")
    private String queueName;
    
    @Value("${app.wmq.publish-concurrency:50}")
    private int publishConcurrency;

    private final ObjectMapper objectMapper;
    private Semaphore publishSemaphore;
    
    public WmqPublisher() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    @PostConstruct
    public void init() {
        this.publishSemaphore = new Semaphore(publishConcurrency);
        log.info("WmqPublisher initialized with publish concurrency limit: {}", publishConcurrency);
    }

    /**
     * Send a batch of processed orders to WMQ with grouping logic.
     * - Groups orders based on business rules
     * - Sends grouped messages to grouped queue
     * - Sends individual messages to standard queue
     * - All publishing done in parallel
     * 
     * @param orders Orders to publish
     * @param executor Virtual thread executor for parallel publishing
     */
    public void sendBatch(List<ProcessedOrder> orders, ExecutorService executor) {
        if (orders.isEmpty()) {
            return;
        }

        // Apply grouping logic
        GroupingResult groupingResult = orderGroupingService.groupOrders(orders);
        
        log.info("Publishing to WMQ: {} grouped messages, {} individual orders",
                groupingResult.groupedMessages().size(),
                groupingResult.individualOrders().size());

        if (wmqEnabled && jmsTemplate != null) {
            sendToRealMqWithGrouping(groupingResult, executor);
        } else {
            sendToMockMqWithGrouping(groupingResult);
        }
    }

    /**
     * Send to real IBM MQ with parallel publishing (concurrency limited).
     */
    private void sendToRealMqWithGrouping(GroupingResult groupingResult, ExecutorService executor) {
        long startTime = System.currentTimeMillis();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        
        int totalMessages = groupingResult.groupedMessages().size() + groupingResult.individualOrders().size();
        log.info("Publishing {} messages to MQ (max {} concurrent)", totalMessages, publishConcurrency);

        // Publish grouped messages in parallel (with semaphore)
        List<CompletableFuture<Void>> groupedFutures = groupingResult.groupedMessages().stream()
                .map(grouped -> CompletableFuture.runAsync(() -> {
                        try {
                            publishSemaphore.acquire();
                            try {
                                String json = objectMapper.writeValueAsString(grouped);
                                jmsTemplate.convertAndSend(queueName, json);
                                successCount.incrementAndGet();
                                log.debug("Sent grouped message '{}' with {} orders to {}",
                                        grouped.groupId(), grouped.orderCount(), queueName);
                            } finally {
                                publishSemaphore.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failCount.incrementAndGet();
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            log.error("Failed to send grouped message '{}': {}",
                                    grouped.groupId(), e.getMessage());
                        }
                }, executor))
                .toList();

        // Publish individual orders in parallel (with semaphore)
        List<CompletableFuture<Void>> individualFutures = groupingResult.individualOrders().stream()
                .map(order -> CompletableFuture.runAsync(() -> {
                        try {
                            publishSemaphore.acquire();
                            try {
                                String json = objectMapper.writeValueAsString(order);
                                jmsTemplate.convertAndSend(queueName, json);
                                successCount.incrementAndGet();
                            } finally {
                                publishSemaphore.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failCount.incrementAndGet();
                        } catch (Exception e) {
                            failCount.incrementAndGet();
                            log.error("Failed to send order {}: {}", order.orderId(), e.getMessage());
                        }
                }, executor))
                .toList();

        // Wait for all to complete
        CompletableFuture.allOf(
                groupedFutures.toArray(new CompletableFuture[0])
        ).join();
        CompletableFuture.allOf(
                individualFutures.toArray(new CompletableFuture[0])
        ).join();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("MQ publish complete in {}ms - Success: {}, Failed: {}", 
                elapsed, successCount.get(), failCount.get());
    }

    /**
     * Mock MQ for testing without IBM MQ.
     */
    private void sendToMockMqWithGrouping(GroupingResult groupingResult) {
        log.info("[MOCK MQ] Publishing: {} grouped, {} individual",
                groupingResult.groupedMessages().size(),
                groupingResult.individualOrders().size());

        // Log grouped messages
        for (GroupedOrderMessage grouped : groupingResult.groupedMessages()) {
            log.info("[MOCK MQ] Grouped '{}' ({}) → {} with {} orders, total: ${}",
                    grouped.groupId(),
                    grouped.groupType(),
                    queueName,
                    grouped.orderCount(),
                    grouped.totalAmount());
        }

        // Log sample individual orders
        groupingResult.individualOrders().stream().limit(3).forEach(order ->
                log.info("[MOCK MQ] Individual {} → {} ${}", 
                        order.orderId(), queueName, order.finalPrice()));

        if (groupingResult.individualOrders().size() > 3) {
            log.info("[MOCK MQ] ... and {} more individual orders",
                    groupingResult.individualOrders().size() - 3);
        }

        // Simulate latency
        try {
            int totalMessages = groupingResult.groupedMessages().size() + 
                               groupingResult.individualOrders().size();
            Thread.sleep(50 + totalMessages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("[MOCK MQ] All messages published successfully");
    }

    /**
     * Send without grouping - each order as individual message.
     * 
     * @param orders Orders to publish
     * @param executor Virtual thread executor for parallel publishing
     */
    public void sendBatchWithoutGrouping(List<ProcessedOrder> orders, ExecutorService executor) {
        if (orders.isEmpty()) {
            return;
        }

        if (wmqEnabled && jmsTemplate != null) {
            sendToRealMq(orders, executor);
        } else {
            sendToMockMq(orders);
        }
    }

    /**
     * Send to real IBM MQ without grouping (concurrency limited).
     */
    private void sendToRealMq(List<ProcessedOrder> orders, ExecutorService executor) {
        log.info("Sending {} orders to IBM MQ queue: {} (max {} concurrent)", 
                orders.size(), queueName, publishConcurrency);
        long startTime = System.currentTimeMillis();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = orders.stream()
                .map(order -> CompletableFuture.runAsync(() -> {
                    try {
                        publishSemaphore.acquire();
                        try {
                            String json = objectMapper.writeValueAsString(order);
                            jmsTemplate.convertAndSend(queueName, json);
                            successCount.incrementAndGet();
                        } finally {
                            publishSemaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        log.error("Failed to send order {}: {}", order.orderId(), e.getMessage());
                    }
                }, executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("MQ send complete in {}ms - Success: {}, Failed: {}", 
                elapsed, successCount.get(), failCount.get());
    }

    /**
     * Mock MQ without grouping.
     */
    private void sendToMockMq(List<ProcessedOrder> orders) {
        log.info("[MOCK MQ] Sending {} orders to queue: {}", orders.size(), queueName);
        
        orders.stream().limit(3).forEach(order ->
                log.debug("[MOCK MQ] Order: {} -> {} -> ${}",
                        order.orderId(), order.status(), order.finalPrice()));
        
        if (orders.size() > 3) {
            log.debug("[MOCK MQ] ... and {} more orders", orders.size() - 3);
        }
        
        // Simulate latency
        try {
            Thread.sleep(50 + orders.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Send a single order to WMQ.
     * 
     * @param order Order to publish
     * @param executor Virtual thread executor
     */
    public void send(ProcessedOrder order, ExecutorService executor) {
        sendBatchWithoutGrouping(List.of(order), executor);
    }
}

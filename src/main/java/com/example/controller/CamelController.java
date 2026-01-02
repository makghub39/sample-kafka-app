package com.example.controller;

import com.example.model.OrderEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * REST controller for managing Apache Camel routes at runtime.
 * Allows starting, stopping, inspecting routes, and sending test events.
 */
@RestController
@RequestMapping("/api/camel")
@RequiredArgsConstructor
@Slf4j
public class CamelController {

    private final CamelContext camelContext;
    private final ProducerTemplate producerTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;
    
    @Value("${app.kafka.topic.order-events:order-events}")
    private String orderEventsTopic;

    /**
     * Get status of all Camel routes.
     */
    @GetMapping("/routes")
    public ResponseEntity<List<Map<String, Object>>> getRoutes() {
        List<Map<String, Object>> routes = camelContext.getRoutes().stream()
                .map(route -> {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("routeId", route.getRouteId());
                    map.put("endpoint", route.getEndpoint().getEndpointUri());
                    map.put("status", getRouteStatus(route.getRouteId()));
                    return map;
                })
                .toList();
        
        return ResponseEntity.ok(routes);
    }

    /**
     * Get status of a specific route.
     */
    @GetMapping("/routes/{routeId}")
    public ResponseEntity<Map<String, Object>> getRoute(@PathVariable String routeId) {
        Route route = camelContext.getRoute(routeId);
        if (route == null) {
            return ResponseEntity.notFound().build();
        }
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("routeId", route.getRouteId());
        result.put("endpoint", route.getEndpoint().getEndpointUri());
        result.put("status", getRouteStatus(routeId));
        result.put("description", route.getDescription() != null ? route.getDescription() : "N/A");
        
        return ResponseEntity.ok(result);
    }

    /**
     * Start a specific route.
     */
    @PostMapping("/routes/{routeId}/start")
    public ResponseEntity<Map<String, String>> startRoute(@PathVariable String routeId) {
        try {
            camelContext.getRouteController().startRoute(routeId);
            log.info("Started route: {}", routeId);
            return ResponseEntity.ok(Map.of(
                    "routeId", routeId,
                    "status", "STARTED",
                    "message", "Route started successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to start route {}: {}", routeId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "routeId", routeId,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Stop a specific route.
     */
    @PostMapping("/routes/{routeId}/stop")
    public ResponseEntity<Map<String, String>> stopRoute(@PathVariable String routeId) {
        try {
            camelContext.getRouteController().stopRoute(routeId);
            log.info("Stopped route: {}", routeId);
            return ResponseEntity.ok(Map.of(
                    "routeId", routeId,
                    "status", "STOPPED",
                    "message", "Route stopped successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to stop route {}: {}", routeId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "routeId", routeId,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Suspend a specific route (pauses without removing from context).
     */
    @PostMapping("/routes/{routeId}/suspend")
    public ResponseEntity<Map<String, String>> suspendRoute(@PathVariable String routeId) {
        try {
            camelContext.getRouteController().suspendRoute(routeId);
            log.info("Suspended route: {}", routeId);
            return ResponseEntity.ok(Map.of(
                    "routeId", routeId,
                    "status", "SUSPENDED",
                    "message", "Route suspended successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to suspend route {}: {}", routeId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "routeId", routeId,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Resume a suspended route.
     */
    @PostMapping("/routes/{routeId}/resume")
    public ResponseEntity<Map<String, String>> resumeRoute(@PathVariable String routeId) {
        try {
            camelContext.getRouteController().resumeRoute(routeId);
            log.info("Resumed route: {}", routeId);
            return ResponseEntity.ok(Map.of(
                    "routeId", routeId,
                    "status", "RESUMED",
                    "message", "Route resumed successfully"
            ));
        } catch (Exception e) {
            log.error("Failed to resume route {}: {}", routeId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "routeId", routeId,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Get Camel context status.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "name", camelContext.getName(),
                "version", camelContext.getVersion(),
                "status", camelContext.getStatus().name(),
                "uptime", camelContext.getUptime(),
                "routeCount", camelContext.getRoutes().size(),
                "startedRoutes", camelContext.getRoutes().stream()
                        .filter(r -> getRouteStatus(r.getRouteId()).equals("Started"))
                        .count()
        ));
    }

    private String getRouteStatus(String routeId) {
        ServiceStatus status = camelContext.getRouteController().getRouteStatus(routeId);
        return status != null ? status.name() : "Unknown";
    }

    // ═══════════════════════════════════════════════════════════════
    // SEND EVENTS VIA CAMEL
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a test event to Kafka via Camel ProducerTemplate.
     * Serializes to JSON before sending.
     * 
     * Example: POST /api/camel/send?tradingPartner=ACME&businessUnit=WEST
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendEvent(
            @RequestParam(defaultValue = "PROCESS_ORDERS") String eventType,
            @RequestParam(defaultValue = "DEFAULT-PARTNER") String tradingPartner,
            @RequestParam(defaultValue = "DEFAULT-UNIT") String businessUnit) {
        
        try {
            OrderEvent event = new OrderEvent(
                    UUID.randomUUID().toString(),
                    eventType,
                    tradingPartner,
                    businessUnit
            );

            // Serialize to JSON before sending
            String jsonPayload = objectMapper.writeValueAsString(event);
            String kafkaUri = String.format("kafka:%s?brokers=%s", orderEventsTopic, kafkaBootstrapServers);
            
            producerTemplate.sendBodyAndHeader(kafkaUri, jsonPayload, "kafka.KEY", event.eventId());

            log.info("Sent event via Camel to Kafka topic {} for trading partner: {}", orderEventsTopic, tradingPartner);

            return ResponseEntity.ok(Map.of(
                    "message", "Event sent via Camel",
                    "eventId", event.eventId(),
                    "eventType", eventType,
                    "tradingPartner", tradingPartner,
                    "businessUnit", businessUnit,
                    "topic", orderEventsTopic
            ));
        } catch (Exception e) {
            log.error("Failed to send event: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Send multiple events to Kafka via Camel.
     * 
     * Example: POST /api/camel/send-batch?eventCount=5&ordersPerEvent=20
     */
    @PostMapping("/send-batch")
    public ResponseEntity<Map<String, Object>> sendBatch(
            @RequestParam(defaultValue = "5") int eventCount,
            @RequestParam(defaultValue = "DEFAULT-PARTNER") String tradingPartner,
            @RequestParam(defaultValue = "PROCESS_ORDERS") String eventType) {

        try {
            String kafkaUri = String.format("kafka:%s?brokers=%s", orderEventsTopic, kafkaBootstrapServers);
            List<String> businessUnits = List.of("WEST", "EAST", "NORTH", "SOUTH", "CENTRAL");
            
            for (int e = 0; e < eventCount; e++) {
                String businessUnit = businessUnits.get(e % businessUnits.size());

                OrderEvent event = new OrderEvent(
                        UUID.randomUUID().toString(),
                        eventType,
                        tradingPartner,
                        businessUnit
                );

                // Serialize to JSON before sending
                String jsonPayload = objectMapper.writeValueAsString(event);
                producerTemplate.sendBodyAndHeader(kafkaUri, jsonPayload, "kafka.KEY", event.eventId());
            }

            log.info("Sent {} events via Camel for trading partner: {}", eventCount, tradingPartner);

            return ResponseEntity.ok(Map.of(
                    "message", "Batch sent via Camel",
                    "eventCount", eventCount,
                    "tradingPartner", tradingPartner,
                    "topic", orderEventsTopic
            ));
        } catch (Exception e) {
            log.error("Failed to send batch: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Send a custom event with specific trading partner and business unit.
     * 
     * Example: POST /api/camel/send-custom
     * Body: {"eventType": "BULK_ORDER", "tradingPartnerName": "ACME", "businessUnitName": "WEST"}
     */
    @PostMapping("/send-custom")
    public ResponseEntity<Map<String, Object>> sendCustomEvent(@RequestBody OrderEventRequest request) {
        
        try {
            OrderEvent event = new OrderEvent(
                    UUID.randomUUID().toString(),
                    request.eventType() != null ? request.eventType() : "PROCESS_ORDERS",
                    request.tradingPartnerName() != null ? request.tradingPartnerName() : "DEFAULT-PARTNER",
                    request.businessUnitName() != null ? request.businessUnitName() : "DEFAULT-UNIT"
            );

            // Serialize to JSON before sending
            String jsonPayload = objectMapper.writeValueAsString(event);
            String kafkaUri = String.format("kafka:%s?brokers=%s", orderEventsTopic, kafkaBootstrapServers);
            producerTemplate.sendBodyAndHeader(kafkaUri, jsonPayload, "kafka.KEY", event.eventId());

            log.info("Sent custom event via Camel: {} for trading partner: {}", 
                    event.eventId(), event.tradingPartnerName());

            return ResponseEntity.ok(Map.of(
                    "message", "Custom event sent via Camel",
                    "eventId", event.eventId(),
                    "eventType", event.eventType(),
                    "tradingPartner", event.tradingPartnerName(),
                    "businessUnit", event.businessUnitName(),
                    "topic", orderEventsTopic
            ));
        } catch (Exception e) {
            log.error("Failed to send custom event: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Request body for custom event.
     */
    public record OrderEventRequest(
            String eventType,
            String tradingPartnerName,
            String businessUnitName
    ) {}
}

package com.smmpanel.controller;

import com.smmpanel.config.KafkaConsumerErrorConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * KAFKA ERROR MONITORING CONTROLLER
 *
 * Provides endpoints for monitoring Kafka error handling:
 * 1. Error metrics and statistics
 * 2. Consumer container health status
 * 3. Dead letter queue monitoring
 * 4. Error handler configuration details
 * 5. Manual error recovery operations
 */
@Slf4j
@RestController
@RequestMapping("/admin/kafka/errors")
@RequiredArgsConstructor
@Tag(name = "Kafka Error Monitoring", description = "Kafka error handling monitoring and management endpoints")
@PreAuthorize("hasRole('ADMIN')")
public class KafkaErrorMonitoringController {

    private final KafkaConsumerErrorConfiguration errorConfiguration;
    private final KafkaListenerEndpointRegistry endpointRegistry;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @GetMapping("/metrics")
    @Operation(summary = "Get Kafka error metrics", 
               description = "Returns comprehensive error handling metrics")
    public ResponseEntity<Map<String, Object>> getErrorMetrics() {
        log.info("Retrieving Kafka error metrics");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) errorConfiguration.kafkaErrorMetrics();
            
            // Enhance metrics with additional information
            Map<String, Object> enhancedMetrics = new HashMap<>(metrics);
            enhancedMetrics.put("timestamp", LocalDateTime.now());
            enhancedMetrics.put("uptime", getSystemUptime());
            enhancedMetrics.put("consumerContainers", getConsumerContainerMetrics());
            
            return ResponseEntity.ok(enhancedMetrics);
            
        } catch (Exception e) {
            log.error("Failed to retrieve error metrics", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to retrieve metrics: " + e.getMessage()));
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Get Kafka consumer health status", 
               description = "Returns health status of all Kafka consumer containers")
    public ResponseEntity<Map<String, Object>> getConsumerHealth() {
        log.info("Checking Kafka consumer health");

        try {
            Map<String, Object> healthStatus = new HashMap<>();
            healthStatus.put("timestamp", LocalDateTime.now());
            healthStatus.put("overallStatus", "HEALTHY");
            
            Map<String, Object> containerStatuses = new HashMap<>();
            int healthyContainers = 0;
            int totalContainers = 0;
            
            for (MessageListenerContainer container : endpointRegistry.getListenerContainers()) {
                totalContainers++;
                String containerId = container.getGroupId() != null ? container.getGroupId() : "unknown-" + totalContainers;
                
                Map<String, Object> containerInfo = new HashMap<>();
                containerInfo.put("running", container.isRunning());
                containerInfo.put("autoStartup", container.isAutoStartup());
                containerInfo.put("phase", container.getPhase());
                containerInfo.put("groupId", container.getGroupId());
                
                if (container.isRunning()) {
                    healthyContainers++;
                    containerInfo.put("status", "HEALTHY");
                } else {
                    containerInfo.put("status", "UNHEALTHY");
                    healthStatus.put("overallStatus", "DEGRADED");
                }
                
                containerStatuses.put(containerId, containerInfo);
            }
            
            healthStatus.put("containers", containerStatuses);
            healthStatus.put("summary", Map.of(
                "total", totalContainers,
                "healthy", healthyContainers,
                "unhealthy", totalContainers - healthyContainers
            ));
            
            return ResponseEntity.ok(healthStatus);
            
        } catch (Exception e) {
            log.error("Failed to check consumer health", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to check health: " + e.getMessage()));
        }
    }

    @GetMapping("/dlq/status")
    @Operation(summary = "Get dead letter queue status", 
               description = "Returns status and metrics for dead letter queues")
    public ResponseEntity<Map<String, Object>> getDlqStatus() {
        log.info("Retrieving DLQ status");

        try {
            Map<String, Object> dlqStatus = new HashMap<>();
            dlqStatus.put("timestamp", LocalDateTime.now());
            
            // This would typically query Kafka Admin API for topic information
            // For now, we'll provide a basic structure
            Map<String, Object> dlqTopics = Map.of(
                "smm.order.processing.dlq", Map.of("enabled", true, "messageCount", "N/A"),
                "smm.video.processing.dlq", Map.of("enabled", true, "messageCount", "N/A"),
                "smm.youtube.processing.dlq", Map.of("enabled", true, "messageCount", "N/A"),
                "smm.offer.assignments.dlq", Map.of("enabled", true, "messageCount", "N/A")
            );
            
            dlqStatus.put("topics", dlqTopics);
            dlqStatus.put("processingEnabled", true);
            dlqStatus.put("retentionDays", 30);
            
            return ResponseEntity.ok(dlqStatus);
            
        } catch (Exception e) {
            log.error("Failed to retrieve DLQ status", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to retrieve DLQ status: " + e.getMessage()));
        }
    }

    @GetMapping("/configuration")
    @Operation(summary = "Get error handler configuration", 
               description = "Returns current error handler configuration details")
    public ResponseEntity<Map<String, Object>> getErrorConfiguration() {
        log.info("Retrieving error handler configuration");

        try {
            Map<String, Object> config = new HashMap<>();
            config.put("timestamp", LocalDateTime.now());
            config.put("errorHandlers", Map.of(
                "default", "DefaultErrorHandler with exponential backoff",
                "deadLetterQueue", "DLQ specialized error handler",
                "highPriority", "High priority error handler",
                "orderProcessing", "Order processing specialized error handler"
            ));
            
            config.put("retryConfiguration", Map.of(
                "maxRetries", "Configurable per handler",
                "backoffStrategy", "Exponential with fixed fallback",
                "nonRetryableExceptions", Map.of(
                    "serialization", "DeserializationException, JsonParseException",
                    "validation", "IllegalArgumentException, ConstraintViolationException",
                    "business", "InsufficientBalanceException, UserNotFoundException",
                    "database", "DataIntegrityViolationException"
                )
            ));
            
            config.put("dlqConfiguration", Map.of(
                "enabled", true,
                "topicMapping", "Auto-generated .dlq suffix",
                "messageEnhancement", "Adds error metadata and headers",
                "retentionDays", 30
            ));
            
            return ResponseEntity.ok(config);
            
        } catch (Exception e) {
            log.error("Failed to retrieve error configuration", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to retrieve configuration: " + e.getMessage()));
        }
    }

    @PostMapping("/containers/{containerId}/restart")
    @Operation(summary = "Restart consumer container", 
               description = "Manually restart a specific consumer container")
    public ResponseEntity<Map<String, Object>> restartContainer(
            @Parameter(description = "Container ID") @PathVariable String containerId) {
        
        log.info("Attempting to restart container: {}", containerId);

        try {
            MessageListenerContainer container = endpointRegistry.getListenerContainer(containerId);
            
            if (container == null) {
                return ResponseEntity.notFound().build();
            }
            
            boolean wasRunning = container.isRunning();
            
            if (wasRunning) {
                container.stop();
                log.info("Stopped container: {}", containerId);
            }
            
            container.start();
            log.info("Started container: {}", containerId);
            
            Map<String, Object> result = Map.of(
                "containerId", containerId,
                "action", "restart",
                "wasRunning", wasRunning,
                "currentlyRunning", container.isRunning(),
                "timestamp", LocalDateTime.now()
            );
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Failed to restart container: {}", containerId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to restart container: " + e.getMessage()));
        }
    }

    @PostMapping("/containers/restart-all")
    @Operation(summary = "Restart all consumer containers", 
               description = "Manually restart all consumer containers")
    public ResponseEntity<Map<String, Object>> restartAllContainers() {
        log.warn("Attempting to restart all consumer containers");

        try {
            int totalContainers = 0;
            int successfulRestarts = 0;
            Map<String, String> results = new HashMap<>();
            
            for (MessageListenerContainer container : endpointRegistry.getListenerContainers()) {
                totalContainers++;
                String containerId = container.getGroupId() != null ? container.getGroupId() : "container-" + totalContainers;
                
                try {
                    boolean wasRunning = container.isRunning();
                    
                    if (wasRunning) {
                        container.stop();
                    }
                    
                    container.start();
                    successfulRestarts++;
                    results.put(containerId, "SUCCESS");
                    
                } catch (Exception e) {
                    log.error("Failed to restart container: {}", containerId, e);
                    results.put(containerId, "FAILED: " + e.getMessage());
                }
            }
            
            Map<String, Object> result = Map.of(
                "action", "restart-all",
                "totalContainers", totalContainers,
                "successfulRestarts", successfulRestarts,
                "results", results,
                "timestamp", LocalDateTime.now()
            );
            
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            log.error("Failed to restart all containers", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to restart containers: " + e.getMessage()));
        }
    }

    @GetMapping("/errors/recent")
    @Operation(summary = "Get recent error summary", 
               description = "Returns summary of recent errors across all consumers")
    public ResponseEntity<Map<String, Object>> getRecentErrors(
            @Parameter(description = "Number of minutes to look back") 
            @RequestParam(defaultValue = "60") int minutes) {
        
        log.info("Retrieving recent errors for last {} minutes", minutes);

        try {
            // In a real implementation, this would query error logs or metrics store
            Map<String, Object> recentErrors = new HashMap<>();
            recentErrors.put("timestamp", LocalDateTime.now());
            recentErrors.put("lookbackMinutes", minutes);
            recentErrors.put("summary", "Error summary would be retrieved from metrics store");
            
            // Placeholder for actual error data
            recentErrors.put("errorsByType", Map.of(
                "deserialization", 0,
                "businessLogic", 0,
                "network", 0,
                "database", 0,
                "unknown", 0
            ));
            
            recentErrors.put("errorsByTopic", Map.of(
                "smm.order.processing", 0,
                "smm.video.processing", 0,
                "smm.youtube.processing", 0
            ));
            
            return ResponseEntity.ok(recentErrors);
            
        } catch (Exception e) {
            log.error("Failed to retrieve recent errors", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to retrieve recent errors: " + e.getMessage()));
        }
    }

    /**
     * Helper method to get consumer container metrics
     */
    private Map<String, Object> getConsumerContainerMetrics() {
        return endpointRegistry.getListenerContainers().stream()
                .collect(Collectors.toMap(
                    container -> container.getGroupId() != null ? container.getGroupId() : "unknown",
                    container -> Map.of(
                        "running", container.isRunning(),
                        "autoStartup", container.isAutoStartup(),
                        "phase", container.getPhase()
                    )
                ));
    }

    /**
     * Helper method to get system uptime (simplified)
     */
    private String getSystemUptime() {
        long uptime = System.currentTimeMillis() - 
                     java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
        long hours = uptime / (60 * 60 * 1000);
        long minutes = (uptime % (60 * 60 * 1000)) / (60 * 1000);
        return String.format("%d hours, %d minutes", hours, minutes);
    }
}

/**
 * ACTUATOR ENDPOINT FOR KAFKA ERROR METRICS
 *
 * Custom actuator endpoint for Kafka error monitoring
 */
@Endpoint(id = "kafka-errors")
@RequiredArgsConstructor
class KafkaErrorsEndpoint {
    
    private final KafkaConsumerErrorConfiguration errorConfiguration;
    
    @ReadOperation
    public Map<String, Object> kafkaErrors() {
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) errorConfiguration.kafkaErrorMetrics();
        
        Map<String, Object> result = new HashMap<>(metrics);
        result.put("timestamp", LocalDateTime.now());
        result.put("status", "available");
        
        return result;
    }
}
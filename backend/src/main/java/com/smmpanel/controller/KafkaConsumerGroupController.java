package com.smmpanel.controller;

import com.smmpanel.service.kafka.KafkaConsumerGroupManagementService;
import com.smmpanel.service.kafka.KafkaConsumerGroupManagementService.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * KAFKA CONSUMER GROUP MANAGEMENT CONTROLLER
 *
 * <p>Provides REST endpoints for consumer group management: 1. Consumer group health monitoring 2.
 * Consumer group information retrieval 3. Consumer group administrative operations 4. Partition
 * assignment monitoring 5. Consumer lag tracking and alerting
 */
@Slf4j
@RestController
@RequestMapping("/admin/kafka/consumer-groups")
@RequiredArgsConstructor
@Tag(
        name = "Kafka Consumer Group Management",
        description = "Consumer group monitoring and management endpoints")
@PreAuthorize("hasRole('ADMIN')")
public class KafkaConsumerGroupController {

    private final KafkaConsumerGroupManagementService managementService;

    @GetMapping
    @Operation(
            summary = "List all consumer groups",
            description = "Returns summary information for all consumer groups")
    public ResponseEntity<List<ConsumerGroupSummary>> listConsumerGroups() {
        log.info("Listing all consumer groups");

        try {
            List<ConsumerGroupSummary> groups = managementService.listConsumerGroups();
            return ResponseEntity.ok(groups);

        } catch (Exception e) {
            log.error("Failed to list consumer groups", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{groupId}")
    @Operation(
            summary = "Get consumer group details",
            description = "Returns detailed information for a specific consumer group")
    public ResponseEntity<ConsumerGroupInfo> getConsumerGroup(
            @Parameter(description = "Consumer group ID") @PathVariable String groupId) {

        log.info("Getting details for consumer group: {}", groupId);

        try {
            ConsumerGroupInfo info = managementService.getConsumerGroupInfo(groupId);
            return ResponseEntity.ok(info);

        } catch (Exception e) {
            log.error("Failed to get consumer group info for: {}", groupId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{groupId}/health")
    @Operation(
            summary = "Check consumer group health",
            description = "Returns health status and metrics for a consumer group")
    public ResponseEntity<ConsumerGroupHealth> checkConsumerGroupHealth(
            @Parameter(description = "Consumer group ID") @PathVariable String groupId) {

        log.info("Checking health for consumer group: {}", groupId);

        try {
            ConsumerGroupHealth health = managementService.checkConsumerGroupHealth(groupId);
            return ResponseEntity.ok(health);

        } catch (Exception e) {
            log.error("Failed to check health for consumer group: {}", groupId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{groupId}/start")
    @Operation(
            summary = "Start consumer group",
            description = "Starts all consumers in the specified consumer group")
    public ResponseEntity<Map<String, Object>> startConsumerGroup(
            @Parameter(description = "Consumer group ID") @PathVariable String groupId) {

        log.info("Starting consumer group: {}", groupId);

        try {
            managementService.startConsumerGroup(groupId);

            Map<String, Object> response =
                    Map.of(
                            "groupId",
                            groupId,
                            "action",
                            "start",
                            "status",
                            "success",
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to start consumer group: {}", groupId, e);

            Map<String, Object> errorResponse =
                    Map.of(
                            "groupId",
                            groupId,
                            "action",
                            "start",
                            "status",
                            "error",
                            "error",
                            e.getMessage(),
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @PostMapping("/{groupId}/stop")
    @Operation(
            summary = "Stop consumer group",
            description = "Stops all consumers in the specified consumer group")
    public ResponseEntity<Map<String, Object>> stopConsumerGroup(
            @Parameter(description = "Consumer group ID") @PathVariable String groupId) {

        log.info("Stopping consumer group: {}", groupId);

        try {
            managementService.stopConsumerGroup(groupId);

            Map<String, Object> response =
                    Map.of(
                            "groupId",
                            groupId,
                            "action",
                            "stop",
                            "status",
                            "success",
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to stop consumer group: {}", groupId, e);

            Map<String, Object> errorResponse =
                    Map.of(
                            "groupId",
                            groupId,
                            "action",
                            "stop",
                            "status",
                            "error",
                            "error",
                            e.getMessage(),
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @PostMapping("/{groupId}/reset-offsets")
    @Operation(
            summary = "Reset consumer group offsets",
            description = "Resets consumer group offsets to earliest for specified topics")
    public ResponseEntity<Map<String, Object>> resetConsumerGroupOffsets(
            @Parameter(description = "Consumer group ID") @PathVariable String groupId,
            @Parameter(description = "List of topics to reset") @RequestBody List<String> topics) {

        log.warn("Resetting offsets for consumer group: {} on topics: {}", groupId, topics);

        try {
            managementService.resetConsumerGroupOffsets(groupId, topics);

            Map<String, Object> response =
                    Map.of(
                            "groupId",
                            groupId,
                            "action",
                            "reset-offsets",
                            "topics",
                            topics,
                            "status",
                            "success",
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to reset offsets for consumer group: {}", groupId, e);

            Map<String, Object> errorResponse =
                    Map.of(
                            "groupId",
                            groupId,
                            "action",
                            "reset-offsets",
                            "topics",
                            topics,
                            "status",
                            "error",
                            "error",
                            e.getMessage(),
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    @GetMapping("/stats")
    @Operation(
            summary = "Get consumer group management statistics",
            description = "Returns overall statistics for consumer group management")
    public ResponseEntity<ConsumerGroupManagementStats> getManagementStats() {
        log.info("Getting consumer group management statistics");

        try {
            ConsumerGroupManagementStats stats = managementService.getManagementStats();
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("Failed to get management statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/health/summary")
    @Operation(
            summary = "Get consumer group health summary",
            description = "Returns summary of health status across all consumer groups")
    public ResponseEntity<Map<String, Object>> getHealthSummary() {
        log.info("Getting consumer group health summary");

        try {
            List<ConsumerGroupSummary> groups = managementService.listConsumerGroups();
            ConsumerGroupManagementStats stats = managementService.getManagementStats();

            long healthyGroups = groups.stream().filter(ConsumerGroupSummary::isHealthy).count();
            long unhealthyGroups = groups.size() - healthyGroups;

            Map<String, Object> summary = new HashMap<>();
            summary.put("totalGroups", groups.size());
            summary.put("healthyGroups", healthyGroups);
            summary.put("unhealthyGroups", unhealthyGroups);
            summary.put(
                    "healthPercentage",
                    groups.isEmpty() ? 100.0 : (healthyGroups * 100.0 / groups.size()));
            summary.put(
                    "totalLag", groups.stream().mapToLong(ConsumerGroupSummary::getTotalLag).sum());
            summary.put("averageLag", stats.getAverageLag());
            summary.put("maxLag", stats.getMaxLag());
            summary.put("totalRebalances", stats.getTotalRebalances());
            summary.put("timestamp", LocalDateTime.now());

            return ResponseEntity.ok(summary);

        } catch (Exception e) {
            log.error("Failed to get health summary", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{groupId}/lag")
    @Operation(
            summary = "Get consumer group lag information",
            description = "Returns detailed lag information for a consumer group")
    public ResponseEntity<Map<String, Object>> getConsumerGroupLag(
            @Parameter(description = "Consumer group ID") @PathVariable String groupId) {

        log.info("Getting lag information for consumer group: {}", groupId);

        try {
            ConsumerGroupInfo info = managementService.getConsumerGroupInfo(groupId);

            Map<String, Object> lagInfo = new HashMap<>();
            lagInfo.put("groupId", groupId);
            lagInfo.put("totalLag", info.getTotalLag());
            lagInfo.put("partitionLags", info.getPartitionLags());
            lagInfo.put("memberCount", info.getMemberCount());
            lagInfo.put("partitionCount", info.getPartitionLags().size());
            lagInfo.put(
                    "averageLagPerPartition",
                    info.getPartitionLags().isEmpty()
                            ? 0.0
                            : info.getTotalLag() / (double) info.getPartitionLags().size());
            lagInfo.put("timestamp", LocalDateTime.now());

            return ResponseEntity.ok(lagInfo);

        } catch (Exception e) {
            log.error("Failed to get lag information for consumer group: {}", groupId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{groupId}/assignments")
    @Operation(
            summary = "Get consumer group partition assignments",
            description = "Returns partition assignment information for a consumer group")
    public ResponseEntity<Map<String, Object>> getPartitionAssignments(
            @Parameter(description = "Consumer group ID") @PathVariable String groupId) {

        log.info("Getting partition assignments for consumer group: {}", groupId);

        try {
            ConsumerGroupInfo info = managementService.getConsumerGroupInfo(groupId);

            Map<String, Object> assignments = new HashMap<>();
            assignments.put("groupId", groupId);
            assignments.put("state", info.getState());
            assignments.put("coordinator", info.getCoordinator());
            assignments.put("partitionAssignor", info.getPartitionAssignor());
            assignments.put("memberCount", info.getMemberCount());
            assignments.put("memberAssignments", info.getMemberAssignments());
            assignments.put("totalPartitions", info.getPartitionOffsets().size());
            assignments.put("timestamp", LocalDateTime.now());

            return ResponseEntity.ok(assignments);

        } catch (Exception e) {
            log.error("Failed to get partition assignments for consumer group: {}", groupId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/health-check")
    @Operation(
            summary = "Trigger health check",
            description = "Manually triggers health check for all consumer groups")
    public ResponseEntity<Map<String, Object>> triggerHealthCheck() {
        log.info("Manually triggering consumer group health check");

        try {
            // The health check runs automatically, but we can trigger an immediate check
            // by calling the service method directly
            List<ConsumerGroupSummary> groups = managementService.listConsumerGroups();

            // Trigger health check for each group
            for (ConsumerGroupSummary group : groups) {
                managementService.checkConsumerGroupHealth(group.getGroupId());
            }

            Map<String, Object> response =
                    Map.of(
                            "action",
                            "health-check",
                            "status",
                            "completed",
                            "groupsChecked",
                            groups.size(),
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Failed to trigger health check", e);

            Map<String, Object> errorResponse =
                    Map.of(
                            "action",
                            "health-check",
                            "status",
                            "error",
                            "error",
                            e.getMessage(),
                            "timestamp",
                            LocalDateTime.now());

            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}

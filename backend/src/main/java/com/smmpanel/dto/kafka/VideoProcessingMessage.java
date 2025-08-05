package com.smmpanel.dto.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * KAFKA MESSAGE: Video Processing Queue Message
 * 
 * Represents a video processing task sent to the Kafka queue.
 * Contains all information needed for async video processing.
 */
@Data
@Builder
@Jacksonized
public class VideoProcessingMessage {

    /**
     * Unique identifier for the order being processed
     */
    @NotNull
    @Positive
    @JsonProperty("orderId")
    private Long orderId;

    /**
     * YouTube video ID extracted from the order link
     */
    @NotNull
    @JsonProperty("videoId") 
    private String videoId;

    /**
     * Original YouTube URL from the order
     */
    @NotNull
    @JsonProperty("originalUrl")
    private String originalUrl;

    /**
     * Target number of views/actions for this order
     */
    @NotNull
    @Positive
    @JsonProperty("targetQuantity")
    private Integer targetQuantity;

    /**
     * Priority level for processing (HIGH, MEDIUM, LOW)
     */
    @NotNull
    @JsonProperty("priority")
    private ProcessingPriority priority;

    /**
     * Type of video processing required
     */
    @NotNull
    @JsonProperty("processingType")
    private VideoProcessingType processingType;

    /**
     * Current processing attempt number (for retry logic)
     */
    @JsonProperty("attemptNumber")
    @Builder.Default
    private Integer attemptNumber = 1;

    /**
     * Maximum number of retry attempts allowed
     */
    @JsonProperty("maxAttempts")
    @Builder.Default
    private Integer maxAttempts = 3;

    /**
     * Timestamp when the message was created
     */
    @NotNull
    @JsonProperty("createdAt")
    private LocalDateTime createdAt;

    /**
     * Timestamp when processing should start (for delayed processing)
     */
    @JsonProperty("scheduleAt")
    private LocalDateTime scheduleAt;

    /**
     * User ID who created the order (for tracking and limits)
     */
    @JsonProperty("userId")
    private Long userId;

    /**
     * Additional processing configuration parameters
     */
    @JsonProperty("processingConfig")
    private Map<String, Object> processingConfig;

    /**
     * Geographic targeting for the processing (if applicable)
     */
    @JsonProperty("geoTargeting")
    private String geoTargeting;

    /**
     * Whether clip creation is enabled for this order
     */
    @JsonProperty("clipCreationEnabled")
    @Builder.Default
    private Boolean clipCreationEnabled = true;

    /**
     * Custom metadata for tracking and debugging
     */
    @JsonProperty("metadata")
    private Map<String, String> metadata;

    /**
     * Processing priority levels
     */
    public enum ProcessingPriority {
        HIGH(1),    // Premium orders, immediate processing
        MEDIUM(2),  // Standard orders
        LOW(3);     // Bulk/batch orders

        private final int level;

        ProcessingPriority(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }
    }

    /**
     * Types of video processing
     */
    public enum VideoProcessingType {
        VIEWS("views"),           // Standard view generation
        LIKES("likes"),           // Like generation  
        COMMENTS("comments"),     // Comment generation
        SUBSCRIBERS("subscribers"), // Subscriber generation
        SHARES("shares");         // Share generation

        private final String type;

        VideoProcessingType(String type) {
            this.type = type;
        }

        public String getType() {
            return type;
        }
    }

    /**
     * Create a standard video processing message
     */
    public static VideoProcessingMessage createStandardMessage(Long orderId, String videoId, String originalUrl, 
                                                             Integer targetQuantity, Long userId) {
        return VideoProcessingMessage.builder()
                .orderId(orderId)
                .videoId(videoId)
                .originalUrl(originalUrl)
                .targetQuantity(targetQuantity)
                .userId(userId)
                .priority(ProcessingPriority.MEDIUM)
                .processingType(VideoProcessingType.VIEWS)
                .createdAt(LocalDateTime.now())
                .attemptNumber(1)
                .maxAttempts(3)
                .clipCreationEnabled(true)
                .build();
    }

    /**
     * Create a high-priority processing message
     */
    public static VideoProcessingMessage createHighPriorityMessage(Long orderId, String videoId, String originalUrl,
                                                                 Integer targetQuantity, Long userId) {
        return VideoProcessingMessage.builder()
                .orderId(orderId)
                .videoId(videoId)
                .originalUrl(originalUrl)
                .targetQuantity(targetQuantity)
                .userId(userId)
                .priority(ProcessingPriority.HIGH)
                .processingType(VideoProcessingType.VIEWS)
                .createdAt(LocalDateTime.now())
                .attemptNumber(1)
                .maxAttempts(5) // More retries for premium orders
                .clipCreationEnabled(true)
                .build();
    }

    /**
     * Create a retry message with incremented attempt number
     */
    public VideoProcessingMessage createRetryMessage() {
        return VideoProcessingMessage.builder()
                .orderId(this.orderId)
                .videoId(this.videoId)
                .originalUrl(this.originalUrl)
                .targetQuantity(this.targetQuantity)
                .priority(this.priority)
                .processingType(this.processingType)
                .attemptNumber(this.attemptNumber + 1)
                .maxAttempts(this.maxAttempts)
                .createdAt(LocalDateTime.now())
                .scheduleAt(this.scheduleAt)
                .userId(this.userId)
                .processingConfig(this.processingConfig)
                .geoTargeting(this.geoTargeting)
                .clipCreationEnabled(this.clipCreationEnabled)
                .metadata(this.metadata)
                .build();
    }

    /**
     * Check if this message has exceeded maximum retry attempts
     */
    public boolean hasExceededMaxAttempts() {
        return attemptNumber > maxAttempts;
    }

    /**
     * Check if this message should be processed immediately
     */
    public boolean shouldProcessImmediately() {
        return scheduleAt == null || scheduleAt.isBefore(LocalDateTime.now()) || scheduleAt.isEqual(LocalDateTime.now());
    }

    /**
     * Get processing delay in seconds (0 if should process immediately)
     */
    public long getProcessingDelaySeconds() {
        if (shouldProcessImmediately()) {
            return 0;
        }
        return java.time.Duration.between(LocalDateTime.now(), scheduleAt).getSeconds();
    }

    /**
     * Add metadata entry
     */
    public void addMetadata(String key, String value) {
        if (metadata == null) {
            metadata = new java.util.HashMap<>();
        }
        metadata.put(key, value);
    }

    /**
     * Add processing configuration
     */
    public void addProcessingConfig(String key, Object value) {
        if (processingConfig == null) {
            processingConfig = new java.util.HashMap<>();
        }
        processingConfig.put(key, value);
    }

    /**
     * Get message routing key for Kafka partitioning
     * Uses orderId to ensure order-related messages go to same partition
     */
    public String getRoutingKey() {
        return orderId.toString();
    }

    /**
     * Get message summary for logging
     */
    public String getSummary() {
        return String.format("VideoProcessingMessage[orderId=%d, videoId=%s, priority=%s, attempt=%d/%d]",
                orderId, videoId, priority, attemptNumber, maxAttempts);
    }
}
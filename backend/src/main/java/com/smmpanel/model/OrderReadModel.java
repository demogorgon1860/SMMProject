package com.smmpanel.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.index.Indexed;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash(value = "OrderReadModel", timeToLive = 3600) // 1 hour TTL
public class OrderReadModel implements Serializable {

    @Id private String id; // order:{orderId}

    @Indexed private Long orderId;

    @Indexed private Long userId;

    @Indexed private String username;

    @Indexed private String status;

    @Indexed private Long serviceId;

    private String serviceName;

    private String link;

    private Integer quantity;

    private Integer startCount;

    private Integer remains;

    private BigDecimal charge;

    private BigDecimal rate;

    // binomCampaignId removed - using direct campaign connection via binomOfferId
    @Indexed private String binomOfferId;

    private String youtubeVideoId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    // Aggregated data from events
    private Integer totalEvents;

    private Integer processedEvents;

    private Integer failedEvents;

    private LocalDateTime lastEventTime;

    private String lastEventType;

    private List<String> eventHistory;

    private Map<String, Integer> eventTypeCounts;

    // Performance metrics
    private Long processingTimeMs;

    private Integer retryCount;

    private String errorMessage;

    // Business metrics
    private BigDecimal totalCost;

    private BigDecimal profitMargin;

    private String customerSegment;

    private Integer customerOrderCount;

    private BigDecimal customerLifetimeValue;

    // Denormalized service data for fast access
    private String serviceCategory;

    private Boolean serviceIsActive;

    private Integer serviceMinQuantity;

    private Integer serviceMaxQuantity;

    // Search optimization
    private String searchableText; // Concatenated searchable fields

    @Indexed private LocalDateTime indexedAt;

    // Methods for view optimization
    public static String generateId(Long orderId) {
        return "order:" + orderId;
    }

    public void updateFromEvent(String eventType) {
        if (this.totalEvents == null) {
            this.totalEvents = 0;
        }
        this.totalEvents++;
        this.lastEventType = eventType;
        this.lastEventTime = LocalDateTime.now();

        if (this.eventTypeCounts == null) {
            this.eventTypeCounts = new java.util.HashMap<>();
        }
        this.eventTypeCounts.merge(eventType, 1, Integer::sum);

        if (this.eventHistory == null) {
            this.eventHistory = new java.util.ArrayList<>();
        }
        if (this.eventHistory.size() >= 10) {
            this.eventHistory.remove(0); // Keep only last 10 events
        }
        this.eventHistory.add(eventType + " at " + LocalDateTime.now());
    }

    public void calculateMetrics() {
        if (this.createdAt != null && this.completedAt != null) {
            this.processingTimeMs =
                    java.time.Duration.between(this.createdAt, this.completedAt).toMillis();
        }

        if (this.charge != null && this.rate != null && this.quantity != null) {
            BigDecimal cost = this.rate.multiply(BigDecimal.valueOf(this.quantity));
            this.totalCost = cost;
            this.profitMargin = this.charge.subtract(cost);
        }

        // Build searchable text for full-text search
        this.searchableText =
                String.join(
                                " ",
                                String.valueOf(orderId),
                                username != null ? username : "",
                                serviceName != null ? serviceName : "",
                                link != null ? link : "",
                                status != null ? status : "")
                        .toLowerCase();

        this.indexedAt = LocalDateTime.now();
    }
}

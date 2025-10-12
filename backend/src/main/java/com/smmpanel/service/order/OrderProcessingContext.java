package com.smmpanel.service.order;

import lombok.Builder;
import lombok.Data;

/**
 * Context object for passing data between async operations. Enhanced with database schema alignment
 * and processing metadata.
 */
@Builder
@Data
public class OrderProcessingContext {
    private Long orderId;
    private String videoId;
    private String orderLink;
    private Integer targetQuantity;
    private Long videoProcessingId;

    // Enhanced fields for database schema alignment
    private ProcessingStrategy processingStrategy;
    // binomCampaignId removed - using direct campaign connection
    private String binomOfferId;
    private Long youtubeAccountId;

    // Additional processing metadata
    private Integer startCount;
    private java.time.LocalDateTime startTime;
    private String metadata;
}

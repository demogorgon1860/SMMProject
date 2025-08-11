package com.smmpanel.service;

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
    private Long binomCampaignId;
    private Long youtubeAccountId;

    // Additional processing metadata
    private Integer startCount;
    private java.time.LocalDateTime startTime;
    private String metadata;
}

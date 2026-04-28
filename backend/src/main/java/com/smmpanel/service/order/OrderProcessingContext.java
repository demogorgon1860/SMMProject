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
    private String orderLink;
    private Integer targetQuantity;

    // Enhanced fields for database schema alignment
    private ProcessingStrategy processingStrategy;

    // Additional processing metadata
    private Integer startCount;
    private java.time.LocalDateTime startTime;
    private String metadata;
}

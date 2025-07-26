package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class OfferAssignmentRequest {
    private Long orderId;
    private Long userId;
    private String trafficSourceId;
    private String offerType;
    private Integer dailyLimit;
    private boolean active;
} 
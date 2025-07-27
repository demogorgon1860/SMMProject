package com.smmpanel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferAssignmentRequest {
    private Long orderId;
    private Long userId;
    private String trafficSourceId;
    private String offerType;
    private Integer dailyLimit;
    private boolean active;
} 
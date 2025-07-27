package com.smmpanel.dto.binom;

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
    private String offerName;
    private String targetUrl;
    private String description;
    private String geoTargeting;
    private Integer requiredClicks;
    private String source;
    private Boolean useFixedCampaign;
} 

package com.smmpanel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomCampaignRequest {
    private Long orderId;
    private String videoId;
    private String targetUrl;
    private Integer targetViews;
    private BigDecimal coefficient;
    private Integer requiredClicks;
    private String trafficSourceId;
    private boolean clipCreated;
    private String clipUrl;
} 
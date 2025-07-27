package com.smmpanel.dto.binom;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BinomCampaignRequest {
    private Long orderId;
    private int targetViews;
    private boolean clipCreated;
    private String targetUrl;
    private String geoTargeting;
} 
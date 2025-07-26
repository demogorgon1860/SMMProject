package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;

@Data
@Builder
public class BinomIntegrationRequest {
    private Long orderId;
    private String campaignId;
    private String trafficSourceId;
    private Integer requiredClicks;
    private BigDecimal paymentPerClick;
    private String targetUrl;
    private boolean active;
} 
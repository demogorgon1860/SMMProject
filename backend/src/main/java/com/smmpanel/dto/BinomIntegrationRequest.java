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
public class BinomIntegrationRequest {
    private Long orderId;
    private String campaignId;
    private String trafficSourceId;
    private Integer requiredClicks;
    private BigDecimal paymentPerClick;
    private String targetUrl;
    private boolean active;
} 
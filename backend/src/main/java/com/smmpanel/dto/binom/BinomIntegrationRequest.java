package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** CRITICAL: Binom Integration Request DTO */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomIntegrationRequest {
    private Long orderId;
    private Integer targetViews;
    private String targetUrl;
    private Boolean clipCreated;
    private String geoTargeting;
    private String clipUrl;
    private java.math.BigDecimal coefficient;

    public boolean isClipCreated() {
        return clipCreated != null && clipCreated;
    }
}

package com.smmpanel.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomIntegrationRequest {
    private Long orderId;
    private String campaignId;
    private Integer requiredClicks;
    private BigDecimal paymentPerClick;
    private String targetUrl;
    private boolean active;
    private boolean clipCreated;
    private BigDecimal coefficient;

    public boolean isClipCreated() {
        return clipCreated;
    }

    public Boolean getClipCreated() {
        return clipCreated;
    }
}

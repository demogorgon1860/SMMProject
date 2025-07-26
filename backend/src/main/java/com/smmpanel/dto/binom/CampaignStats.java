package com.smmpanel.dto.binom;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class CampaignStats {
    private String campaignId;
    private int clicks;
    private int conversions;
    private BigDecimal cost;
    private BigDecimal revenue;
    private boolean cached;
    private String error;
}
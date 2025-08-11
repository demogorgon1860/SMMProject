package com.smmpanel.dto.binom;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

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

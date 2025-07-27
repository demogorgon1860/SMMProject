package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignStatsResponse {
    private String campaignId;
    private Long clicks;
    private Long conversions;
    private BigDecimal cost;
    private BigDecimal revenue;
    private String status;
} 

package com.smmpanel.dto.binom;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignStatsResponse {
    private String campaignId;
    private String campaignName;
    private Long clicks;
    private Long uniqueClicks;
    private Long conversions;
    private Long uniqueConversions;
    private BigDecimal cost;
    private BigDecimal revenue;
    private BigDecimal profit;
    private BigDecimal roi;
    private BigDecimal cpc;
    private BigDecimal cpm;
    private BigDecimal ctr;
    private BigDecimal conversionRate;
    private BigDecimal epc;
    private String status;
    // Traffic source removed - campaigns are pre-configured
    private Long affiliateNetworkId;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
}

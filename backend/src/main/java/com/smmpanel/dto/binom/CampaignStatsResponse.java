package com.smmpanel.dto.binom;

import java.math.BigDecimal;
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
    private Long clicks;
    private Long conversions;
    private BigDecimal cost;
    private BigDecimal revenue;
    private String status;
}

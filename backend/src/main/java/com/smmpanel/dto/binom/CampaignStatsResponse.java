package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Response containing statistics for a campaign in Binom
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignStatsResponse {
    private String campaignId;
    private Integer clicks;
    private Integer views;
    private Integer conversions;
    private Double cost;
    private Double revenue;
    private Double profit;
    private Double roi;
    private String lastUpdated;
}

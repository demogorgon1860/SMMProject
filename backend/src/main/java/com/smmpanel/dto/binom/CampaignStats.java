package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignStats {
    private String campaignId;
    private Integer clicks;
    private Integer conversions;
    private Double spend;
    private Double revenue;
}
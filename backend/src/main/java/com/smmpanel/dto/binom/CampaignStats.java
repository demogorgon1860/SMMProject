package com.smmpanel.dto.binom;

import lombok.*;

/**
 * Статистика кампании (существующий)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignStats {
    private String campaignId;
    private Integer clicks;
    private Integer conversions;
    private Double ctr;
    private Double cost;
    private String status;
}
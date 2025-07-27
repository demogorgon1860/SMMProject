package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for creating a campaign in Binom
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCampaignResponse {
    private String campaignId;
    private String name;
    private String status;
    private String geoTargeting;
}

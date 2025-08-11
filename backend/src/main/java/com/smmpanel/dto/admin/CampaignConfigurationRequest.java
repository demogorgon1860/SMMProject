package com.smmpanel.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignConfigurationRequest {
    private String campaignId; // From Binom (e.g., "CAMP_CLICKADOO_001")
    private String campaignName; // Display name
    private String geoTargeting; // Geographic targeting
    private Integer priority; // Priority order (1, 2, 3)
    private Integer weight; // Weight for distribution
    private Boolean active; // Whether to use this campaign
    private String description; // Admin notes
}

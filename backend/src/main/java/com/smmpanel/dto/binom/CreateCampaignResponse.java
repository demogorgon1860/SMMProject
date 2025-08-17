package com.smmpanel.dto.binom;

import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response for creating a campaign in Binom */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCampaignResponse {
    private String campaignId;
    private String name;
    private String description;
    private String status;
    private Long trafficSourceId;
    private Long affiliateNetworkId;
    private List<String> geoTargeting;
    private String costModel;
    private Double costValue;
    private String landingPageUrl;
    private String category;
    private Boolean useTokens;
    private List<String> tokens;
    private String rotationType;
    private Boolean isArchived;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String trackingUrl;
}

package com.smmpanel.dto.binom;

import java.util.List;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignInfoResponse {
    private String campaignId;
    private String name;
    private String status;
    private String trafficSource;
    private String landingPage;
    private List<String> offers;
    private String costModel;
    private Double costValue;
    private String geoTargeting;
    private Boolean isActive;
    private String createdAt;
    private String updatedAt;
    private CampaignStats stats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CampaignStats {
        private Long clicks;
        private Long conversions;
        private Double cost;
        private Double revenue;
        private Double roi;
        private Double ctr;
        private Double cr;
    }
}
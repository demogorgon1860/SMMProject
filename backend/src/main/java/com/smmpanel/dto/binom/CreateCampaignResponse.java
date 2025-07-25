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
    private String trafficSourceId;
    private String geoTargeting;
    
    // Manual builder method since Lombok annotation processing is broken
    public static CreateCampaignResponseBuilder builder() {
        return new CreateCampaignResponseBuilder();
    }
    
    public static class CreateCampaignResponseBuilder {
        private String campaignId;
        private String name;
        private String status;
        private String trafficSourceId;
        private String geoTargeting;
        
        public CreateCampaignResponseBuilder campaignId(String campaignId) { this.campaignId = campaignId; return this; }
        public CreateCampaignResponseBuilder name(String name) { this.name = name; return this; }
        public CreateCampaignResponseBuilder status(String status) { this.status = status; return this; }
        public CreateCampaignResponseBuilder trafficSourceId(String trafficSourceId) { this.trafficSourceId = trafficSourceId; return this; }
        public CreateCampaignResponseBuilder geoTargeting(String geoTargeting) { this.geoTargeting = geoTargeting; return this; }
        
        public CreateCampaignResponse build() {
            CreateCampaignResponse response = new CreateCampaignResponse();
            response.campaignId = this.campaignId;
            response.name = this.name;
            response.status = this.status;
            response.trafficSourceId = this.trafficSourceId;
            response.geoTargeting = this.geoTargeting;
            return response;
        }
    }
}

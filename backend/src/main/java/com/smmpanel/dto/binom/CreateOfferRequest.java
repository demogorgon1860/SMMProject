package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request for creating an offer in Binom
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferRequest {
    
    @NotBlank(message = "Offer name is required")
    private String name;
    
    @NotBlank(message = "Offer URL is required")
    @Pattern(regexp = "^https?://.*", message = "Invalid URL format")
    private String url;
    
    private String description;
    
    @Builder.Default
    private String geoTargeting = "US";
    
    @Builder.Default
    private String category = "SMM_YOUTUBE";
    
    @Builder.Default
    private String status = "ACTIVE";
    
    private String landingPageUrl;
    private Double payout;
    private String payoutType;
    
    // Manual getters since Lombok annotation processing is broken
    public String getName() { return name; }
    public String getUrl() { return url; }
    public String getDescription() { return description; }
    public String getGeoTargeting() { return geoTargeting; }
    public String getStatus() { return status; }
    public String getLandingPageUrl() { return landingPageUrl; }
    public Double getPayout() { return payout; }
    public String getPayoutType() { return payoutType; }
}

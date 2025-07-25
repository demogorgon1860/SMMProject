package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

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
}

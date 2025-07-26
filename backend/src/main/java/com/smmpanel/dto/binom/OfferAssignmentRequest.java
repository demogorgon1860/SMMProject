package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request for assigning an offer to a campaign in Binom
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferAssignmentRequest {
    
    @NotNull(message = "Order ID is required")
    private Long orderId;
    
    @NotBlank(message = "Offer name is required")
    private String offerName;
    
    @NotBlank(message = "Target URL is required")
    private String targetUrl;
    
    private String description;
    
    @Builder.Default
    private String geoTargeting = "US";
    
    @NotBlank(message = "Source is required")
    private String source; // VIDEO_PROCESSING_CLIP, VIDEO_PROCESSING_ORIGINAL, MANUAL
    
    private String campaignId;
    private Boolean useFixedCampaign;
    
    // Additional fields for the new BinomService
    private Integer clicksLimit;
    private String status;
    private String action;
    private String reason;
}

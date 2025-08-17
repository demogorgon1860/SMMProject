package com.smmpanel.dto.binom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferRequest {

    @NotBlank(message = "Offer name is required")
    private String name;

    @NotBlank(message = "Offer URL is required")
    private String url;

    private String description;

    @NotNull(message = "Affiliate network ID is required") private Long affiliateNetworkId;

    private List<String> geoTargeting;

    @Builder.Default private String type = "REDIRECT";

    @Builder.Default private String status = "ACTIVE";

    private String category;

    private Double payout;

    private String payoutCurrency;

    private String payoutType;

    private String conversionCap;

    private Boolean requiresApproval;

    private String notes;

    @Builder.Default private Boolean isArchived = false;
}

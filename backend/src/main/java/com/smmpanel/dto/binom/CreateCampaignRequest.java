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
public class CreateCampaignRequest {

    @NotBlank(message = "Campaign name is required")
    private String name;

    private String description;

    @NotNull(message = "Traffic source ID is required") private Long trafficSourceId;

    private Long affiliateNetworkId;

    private List<String> geoTargeting;

    @Builder.Default private String status = "ACTIVE";

    @Builder.Default private String costModel = "CPC";

    private Double costValue;

    private String landingPageUrl;

    private String category;

    private String notes;

    private Boolean useTokens;

    private List<String> tokens;

    private String rotationType;

    @Builder.Default private Boolean isArchived = false;

    private Integer targetViews;

    public String getName() {
        return this.name;
    }
}

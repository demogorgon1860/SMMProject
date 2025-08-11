package com.smmpanel.dto.binom;

import jakarta.validation.constraints.NotBlank;
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

    private String offerId; // Add this field

    private String description;

    @Builder.Default private String geoTargeting = "US";

    @Builder.Default private String status = "ACTIVE";

    private String category;
    private Double costModel;
    private String notes;
}

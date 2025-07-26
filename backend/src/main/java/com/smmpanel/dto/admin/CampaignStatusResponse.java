package com.smmpanel.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampaignStatusResponse {
    private Long id;
    private String campaignId;
    private String campaignName;
    private String geoTargeting;
    private Integer priority;
    private Integer weight;
    private Boolean active;
    private String description;
    private Boolean connected;        // Whether reachable in Binom
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
} 
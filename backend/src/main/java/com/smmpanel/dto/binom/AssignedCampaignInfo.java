package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for assigned campaign information
 * Compatible with Perfect Panel response format
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignedCampaignInfo {
    
    private String campaignId;
    private String campaignName;
    private Long trafficSourceId;
    private String trafficSourceName;
    private String geoTargeting;
    private Integer weight;
    private Integer priority;
    private Boolean active;
    private LocalDateTime assignedAt;
    private String status;
    private Integer dailyClicks;
    private Integer totalClicks;
    private String description;
}

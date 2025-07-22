package com.smmpanel.dto.binom;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO для информации о назначенной кампании
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignedCampaignInfo {
    
    private String campaignId;
    private String campaignName;
    private Long trafficSourceId;
    private String offerId;
    private Integer clicksRequired;
    private String status;
    private java.time.LocalDateTime createdAt;
}

package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCampaignRequest {
    private String name;
    private String url;
    private String trafficSourceId;
    private String countryCode;
    private Integer clicksLimit;
    private Integer dailyLimit;
}
package com.smmpanel.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrafficSourceDto {
    private Long id;
    private String name;
    private String sourceId;
    private Integer weight;
    private Integer dailyLimit;
    private Integer clicksUsedToday;
    private String geoTargeting;
    private Boolean active;
    private BigDecimal performanceScore;
}
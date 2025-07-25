package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request for Binom integration with order and targeting information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomIntegrationRequest {
    
    @NotNull
    private Long orderId;
    
    @NotNull
    private String targetUrl;
    
    @NotNull
    private Integer targetViews;
    
    @NotNull
    private BigDecimal coefficient;
    
    private Boolean clipCreated;
    private String clipUrl;
    private String geoTargeting;
}

package com.smmpanel.dto.binom;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** CRITICAL: Binom Integration Response DTO */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomIntegrationResponse {
    private boolean success;
    private Long orderId;
    private String offerId;
    private List<String> campaignIds;
    private Integer totalClicksRequired;
    private BigDecimal coefficient;
    private String targetUrl;
    private Boolean clipCreated;
    private String message;
    private String errorMessage;
    private String status;
    private String campaignId;
    private String errorCode;
}

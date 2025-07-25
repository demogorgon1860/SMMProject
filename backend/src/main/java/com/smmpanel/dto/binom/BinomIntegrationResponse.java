package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response for Binom integration with campaign and offer information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomIntegrationResponse {
    private String status;
    private String message;
    private Long orderId;
    private String offerId;
    private List<String> campaignIds;
    private Integer clicksRequired;
    private BigDecimal coefficient;
    private String targetUrl;
}

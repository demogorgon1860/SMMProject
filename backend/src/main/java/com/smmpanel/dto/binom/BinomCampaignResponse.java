package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinomCampaignResponse {
    private String campaignId;
    private String offerId;
    private int clicksRequired;
    private BigDecimal coefficient;
    private boolean success;
    private String errorMessage;
} 
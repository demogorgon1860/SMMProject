package com.smmpanel.dto.binom;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class BinomCampaignResponse {
    private String campaignId;
    private String offerId;
    private int clicksRequired;
    private BigDecimal coefficient;
    private boolean success;
    private String errorMessage;
} 
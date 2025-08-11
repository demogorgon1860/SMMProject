package com.smmpanel.dto.binom;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

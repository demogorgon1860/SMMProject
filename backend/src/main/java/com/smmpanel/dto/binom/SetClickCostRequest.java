package com.smmpanel.dto.binom;

import java.math.BigDecimal;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetClickCostRequest {
    private String campaignId;
    private BigDecimal cost;
    private String costModel; // CPC, CPM, CPA
    private String currency;
    private String notes;
}
package com.smmpanel.dto.binom;

import java.math.BigDecimal;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetClickCostResponse {
    private String campaignId;
    private BigDecimal cost;
    private String costModel;
    private String currency;
    private String status;
    private String message;
    private Boolean success;
}
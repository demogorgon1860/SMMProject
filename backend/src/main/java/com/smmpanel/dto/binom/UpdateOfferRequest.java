package com.smmpanel.dto.binom;

import java.util.List;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateOfferRequest {
    private String name;
    private String url;
    private String description;
    private String status;
    private Long affiliateNetworkId;
    private List<String> geoTargeting;
    private String type;
    private String category;
    private Double payout;
    private String payoutCurrency;
    private String payoutType;
    private String conversionCap;
    private Boolean requiresApproval;
    private String notes;
    private Boolean isActive;
}
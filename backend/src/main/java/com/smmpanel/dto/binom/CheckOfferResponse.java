package com.smmpanel.dto.binom;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckOfferResponse {
    private boolean exists;
    private String offerId;
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
    private Boolean requiresApproval;
    private Boolean isArchived;
    private String message;
}

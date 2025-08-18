package com.smmpanel.dto.binom;

import java.util.List;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffersListResponse {
    private List<OfferInfo> offers;
    private int totalCount;
    private String status;
    private String message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OfferInfo {
        private String offerId;
        private String name;
        private String url;
        private String status;
        private String type;
        private String category;
        private Double payout;
        private String payoutCurrency;
        private String payoutType;
        private List<String> geoTargeting;
        private Boolean isActive;
        private String affiliateNetwork;
    }
}

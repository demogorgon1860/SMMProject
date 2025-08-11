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
public class OfferAssignmentResponse {
    private Long orderId;
    private String offerId;
    private String offerName;
    private String targetUrl;
    private String status;
    private String message;
    private Integer campaignsCreated;
    private List<String> campaignIds;
}

package com.smmpanel.dto.binom;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO для ответа после создания оффера
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferAssignmentResponse {
    
    private String offerId;
    private String offerName;
    private String targetUrl;
    private Long orderId;
    private Integer campaignsCreated;
    private java.util.List<String> campaignIds;
    private String status;
    private String message;
}

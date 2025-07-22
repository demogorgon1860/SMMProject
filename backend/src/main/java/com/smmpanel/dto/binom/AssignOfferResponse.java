package com.smmpanel.dto.binom;

import lombok.*;

/**
 * Ответ на назначение оффера кампании
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignOfferResponse {
    private String campaignId;
    private String offerId;
    private String status;
    private String message;
}

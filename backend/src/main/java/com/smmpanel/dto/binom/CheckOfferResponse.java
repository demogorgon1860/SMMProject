package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Check Offer Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckOfferResponse {
    private boolean exists;
    private String offerId;
}

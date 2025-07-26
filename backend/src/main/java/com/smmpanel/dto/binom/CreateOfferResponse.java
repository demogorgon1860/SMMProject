package com.smmpanel.dto.binom;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Create Offer Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferResponse {
    private String offerId;
    private String name;
    private String url;
}

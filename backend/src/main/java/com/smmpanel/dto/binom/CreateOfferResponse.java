package com.smmpanel.dto.binom;

import lombok.*;

/**
 * Ответ на создание оффера в Binom
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferResponse {
    private String offerId;
    private String name;
    private String url;
    private String status;
    private String message;
}

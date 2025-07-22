package com.smmpanel.dto.binom;

import lombok.*;

/**
 * Ответ на проверку существования оффера
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckOfferResponse {
    private Boolean exists;
    private String offerId;
    private String name;
    private String url;
}

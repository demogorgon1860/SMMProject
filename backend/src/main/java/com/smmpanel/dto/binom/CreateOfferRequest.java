package com.smmpanel.dto.binom;

import lombok.*;

/**
 * Запрос на создание оффера в Binom
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferRequest {
    private String name;
    private String url;
    private String description;
    private String geoTargeting;
    private String category;
}

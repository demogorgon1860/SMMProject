package com.smmpanel.dto.binom;

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
    private String id;
    private String status;
    private String name;
    private String url;
    private Boolean active;
    private String message;
}

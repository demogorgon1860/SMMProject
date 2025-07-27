package com.smmpanel.dto.binom;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOfferResponse {
    private boolean success;
    private String offerId;
    private String name;
    private String url;
    private String status;
    private String message;
} 

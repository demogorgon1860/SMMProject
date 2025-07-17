package com.smmpanel.dto.cryptomus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentResponse {
    private String uuid;
    private String orderId;
    private String amount;
    private String currency;
    private String url;
    private String status;
}

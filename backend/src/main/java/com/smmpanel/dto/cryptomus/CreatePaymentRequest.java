package com.smmpanel.dto.cryptomus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentRequest {
    private String amount;
    private String currency;
    private String network;
    private String orderId;
    private String urlReturn;
    private String urlCallback;
    private String merchantId;
    private Integer isSubtract;
}
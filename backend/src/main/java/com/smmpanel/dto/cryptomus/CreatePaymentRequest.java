package com.smmpanel.dto.cryptomus;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentRequest {
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String orderId;
    private String callbackUrl;
    private String successUrl;
    private String failUrl;
}

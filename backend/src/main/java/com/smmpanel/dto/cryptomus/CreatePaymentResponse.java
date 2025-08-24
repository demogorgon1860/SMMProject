package com.smmpanel.dto.cryptomus;

import java.math.BigDecimal;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentResponse {
    private String uuid;
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String paymentStatus;
    private String url;
    private String address;
    private String network;
    private String txid;
    private String status;
}

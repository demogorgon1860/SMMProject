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
public class PaymentInfoResponse {
    private String uuid;
    private String orderId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String paymentStatus;
    private String url;
    private String address;
    private String network;
    private String txid;
}

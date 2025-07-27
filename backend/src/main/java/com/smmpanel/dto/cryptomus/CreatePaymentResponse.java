package com.smmpanel.dto.cryptomus;

import lombok.*;
import java.math.BigDecimal;

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
    private String txid;
    private String status;
} 

package com.smmpanel.dto.cryptomus;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptomusWebhook {
    @JsonProperty("order_id")
    private String orderId;

    private String uuid;
    private String status;
    private String amount;
    private String currency;

    @JsonProperty("payer_amount")
    private String payerAmount;

    @JsonProperty("payer_currency")
    private String payerCurrency;

    @JsonProperty("network")
    private String network;

    @JsonProperty("txid")
    private String txid;
}

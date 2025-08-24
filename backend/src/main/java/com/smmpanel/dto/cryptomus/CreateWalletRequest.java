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
public class CreateWalletRequest {
    private String currency;
    private String network;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("url_callback")
    private String urlCallback;
}

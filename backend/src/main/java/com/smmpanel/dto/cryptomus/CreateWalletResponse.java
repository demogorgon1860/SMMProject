package com.smmpanel.dto.cryptomus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateWalletResponse {
    private String walletUuid;
    private String uuid;
    private String address;
    private String network;
    private String currency;
    private String url;
    private String orderId;
}

package com.smmpanel.dto.cryptomus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal amount;

    private String currency;

    @JsonProperty("order_id")
    private String orderId;

    // Optional fields
    private String network;

    @JsonProperty("url_return")
    private String urlReturn;

    @JsonProperty("url_success")
    private String urlSuccess;

    @JsonProperty("url_callback")
    private String urlCallback;

    private Integer lifetime;

    @JsonProperty("to_currency")
    private String toCurrency;

    private Integer subtract;

    @JsonProperty("discount_percent")
    @JsonSerialize(using = ToStringSerializer.class)
    private BigDecimal discountPercent;

    @JsonProperty("is_payment_multiple")
    private Boolean isPaymentMultiple;

    private String comment;

    @JsonProperty("additional_data")
    private String additionalData;
}

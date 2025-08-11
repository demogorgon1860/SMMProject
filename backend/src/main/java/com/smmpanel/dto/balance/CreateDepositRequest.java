package com.smmpanel.dto.balance;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDepositRequest {

    @NotNull(message = "Amount is required") @DecimalMin(value = "5.0", message = "Minimum deposit amount is $5.00")
    @DecimalMax(value = "10000.0", message = "Maximum deposit amount is $10,000.00")
    private BigDecimal amount;

    @NotBlank(message = "Currency is required")
    @Pattern(
            regexp = "^(BTC|ETH|USDT|LTC|USDC)$",
            message = "Supported currencies: BTC, ETH, USDT, LTC, USDC")
    private String currency;
}

package com.smmpanel.dto.balance;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request DTO for currency conversion */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyConversionRequest {

    @NotNull(message = "Amount is required") @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "Source currency code is required")
    private String fromCurrency;

    @NotBlank(message = "Target currency code is required")
    private String toCurrency;

    /** Whether to format the result with currency symbol */
    @Builder.Default private boolean format = true;
}

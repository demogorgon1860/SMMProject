package com.smmpanel.dto.admin;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoefficientUpdateRequest {
    @NotNull(message = "With clip coefficient is required")
    @DecimalMin(value = "1.0", message = "Coefficient must be at least 1.0")
    private BigDecimal withClip;
    
    @NotNull(message = "Without clip coefficient is required")
    @DecimalMin(value = "1.0", message = "Coefficient must be at least 1.0")
    private BigDecimal withoutClip;
}

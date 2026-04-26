package com.smmpanel.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Typed body for {@code PUT /api/v2/admin/users/{userId}/balance}. Replaces an earlier {@code
 * Map<String, Object>} approach that ClassCast'd whenever a JSON integer (e.g. {@code 5}) was sent
 * — Jackson deserializes integers to {@link Integer}, not {@link Double}.
 *
 * <p>Using {@link BigDecimal} preserves precision for fractional dollar amounts and never throws on
 * numeric type mismatch.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceAdjustmentRequest {

    /** Signed delta. Positive credits the wallet, negative debits it. Cannot be zero. */
    @NotNull(message = "amount is required") private BigDecimal amount;

    /** Human-readable note recorded in the balance audit log. */
    @Size(max = 500, message = "reason must be 500 characters or less")
    private String reason;
}

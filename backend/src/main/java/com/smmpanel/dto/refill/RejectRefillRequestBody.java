package com.smmpanel.dto.refill;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for {@code POST /api/v2/admin/refill-requests/{id}/reject}. Reason is required and shown to
 * the user — keep it customer-facing and specific.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RejectRefillRequestBody {

    @NotBlank(message = "Rejection reason is required")
    @Size(min = 5, max = 500, message = "Reason must be 5–500 characters")
    private String reason;
}

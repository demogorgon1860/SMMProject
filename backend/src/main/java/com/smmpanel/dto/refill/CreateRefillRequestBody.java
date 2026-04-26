package com.smmpanel.dto.refill;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body for {@code POST /api/v1/orders/{id}/refill}. All fields optional. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRefillRequestBody {

    /**
     * Optional context the user wants the admin to see (e.g. "lost ~30% of likes after 5 days").
     */
    @Size(max = 500, message = "Note must be 500 characters or less")
    private String note;
}

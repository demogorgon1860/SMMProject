package com.smmpanel.dto.refill;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for the batch refill submit ({@code POST /api/v1/refill/requests}). The Refill page lets the
 * customer paste several order numbers at once ("29931, 29932, …"); the panel auto-checks each
 * order's drop and submits the dropped amount for admin approval. Per-order outcomes come back in
 * {@link RefillBatchResponse} — one bad id never fails the whole batch.
 */
@Data
@NoArgsConstructor
public class RefillBatchRequest {

    /** Order ids to submit for refill. De-duplicated and capped server-side. */
    @NotEmpty(message = "At least one order number is required")
    @Size(max = 100, message = "Up to 100 orders can be submitted at once")
    private List<Long> orderIds;

    /** Optional shared note attached to every request in the batch. */
    private String note;
}

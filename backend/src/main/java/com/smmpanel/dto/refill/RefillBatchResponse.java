package com.smmpanel.dto.refill;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-order outcome of a batch refill submit. Each input order id maps to exactly one {@link Item}
 * so the Refill page can render a row-by-row result ("✓ #29931 — checking drop…", "✗ #29933 —
 * already submitted"). The batch never fails as a whole: an ineligible order yields {@code
 * accepted=false} with a message, the rest still go through.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefillBatchResponse {

    private List<Item> results;

    /** Convenience counts for the page header. */
    private int accepted;
    private int rejected;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {
        private Long orderId;

        /** True when a refill request was created (or an in-flight one was returned). */
        private boolean accepted;

        /** Resulting request status when accepted (CHECKING / PENDING). Null when rejected. */
        private String status;

        /** Created/returned request id when accepted. Null when rejected. */
        private Long requestId;

        /** Customer-facing reason when rejected (e.g. "already submitted", "not eligible"). */
        private String message;
    }
}

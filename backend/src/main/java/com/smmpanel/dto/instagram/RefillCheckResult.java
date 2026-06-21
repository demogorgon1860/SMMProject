package com.smmpanel.dto.instagram;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client-side outcome of kicking off a refill check. Tells the caller whether the bot accepted the
 * order and, when multi-bot, which instance answered so status polling can pin to it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefillCheckResult {
    /** True when a bot queued the check for this order (a job id was returned). */
    private boolean accepted;

    /** Bot instance that accepted the order — status polling must hit this same instance. */
    private String instanceUrl;

    /** Bot job id ({@code rf_...}) to poll. */
    private String jobId;

    /** Reason when {@link #accepted} is false (skip reason, queue full, disabled, all down). */
    private String error;
}

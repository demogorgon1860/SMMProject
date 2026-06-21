package com.smmpanel.dto.instagram;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result of polling a refill job, distinguishing the three cases the scheduler must treat
 * differently:
 *
 * <ul>
 *   <li>{@code job != null} — the bot returned the job (HTTP 200); inspect its status/reports.
 *   <li>{@code jobMissing == true} — a definitive HTTP 404: the bot no longer has this job (restart
 *       / LRU eviction). Safe to FAIL the check after the lost-job grace.
 *   <li>otherwise — a transient failure (network blip, circuit breaker open, non-200). NOT proof
 *       the job is gone; keep polling until the overall max-age ceiling.
 * </ul>
 */
@Getter
@AllArgsConstructor
public class RefillStatusOutcome {

    private final RefillJobDto job;
    private final boolean jobMissing;

    public static RefillStatusOutcome found(RefillJobDto job) {
        return new RefillStatusOutcome(job, false);
    }

    public static RefillStatusOutcome missing() {
        return new RefillStatusOutcome(null, true);
    }

    public static RefillStatusOutcome transientError() {
        return new RefillStatusOutcome(null, false);
    }
}

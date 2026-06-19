package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Per-AdsPower-profile error tally over a time window, as returned by the bot's {@code GET
 * /api/profiles/errors} endpoint and surfaced in the System Health digest. {@code failed} and
 * {@code profileFailed} are kept separate so operators can tell a broken/banned account
 * (profile_failed) from a generic action failure (failed). Ranking uses their sum.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProfileErrorStat {

    private String profileAdsPowerId;
    private int failed;
    private int profileFailed;
    private int totalActions;
    private String lastAt; // ISO-8601 timestamp of the most recent action in the window

    /** Total profile-fault errors used for ranking. */
    public int errorCount() {
        return failed + profileFailed;
    }

    /**
     * Merge two stats for the SAME profile across bot instances (each instance may own a separate
     * Postgres). Sums counts and keeps the latest {@code lastAt} (ISO-8601 strings sort
     * lexicographically when same-format UTC).
     */
    public ProfileErrorStat combine(ProfileErrorStat other) {
        if (other == null) return this;
        return ProfileErrorStat.builder()
                .profileAdsPowerId(profileAdsPowerId)
                .failed(failed + other.failed)
                .profileFailed(profileFailed + other.profileFailed)
                .totalActions(totalActions + other.totalActions)
                .lastAt(maxIso(lastAt, other.lastAt))
                .build();
    }

    private static String maxIso(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }
}

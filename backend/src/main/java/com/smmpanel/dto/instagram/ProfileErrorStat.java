package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

/**
 * Per-AdsPower-profile error tally over a time window, as returned by the bot's {@code GET
 * /api/profiles/errors} endpoint and surfaced in the System Health digest. The fault classes are
 * kept separate so operators can act on each: {@code loggedOut} (the login-required subset of
 * {@code profileFailed}) means the profile needs a manual re-login; {@code transientAction} means
 * the profile kept failing to find the action button/element (a logged-out or action-blocked
 * profile also surfaces here); {@code profileFailed} is all account-level failures (incl. bans);
 * {@code failed} is a generic action failure (often a target issue). The bot only returns profiles
 * that qualify (>= minErrors transient_action OR >= 1 logged-out); ranking puts logged-out first.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProfileErrorStat {

    private String profileAdsPowerId;
    private int failed;
    private int profileFailed;
    private int loggedOut;
    private int transientAction;
    private int totalActions;
    private String lastAt; // ISO-8601 timestamp of the most recent action in the window

    /**
     * Headline total of fault rows for display. {@code loggedOut} is a labelled SUBSET of {@code
     * profileFailed}, so it is intentionally NOT added here (that would double-count). Ranking is
     * done by {@link com.smmpanel.client.InstagramBotClient} with an explicit multi-key comparator
     * (logged-out first, then transient), not by this sum.
     */
    public int errorCount() {
        return failed + profileFailed + transientAction;
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
                .loggedOut(loggedOut + other.loggedOut)
                .transientAction(transientAction + other.transientAction)
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

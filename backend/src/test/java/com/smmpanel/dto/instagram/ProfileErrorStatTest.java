package com.smmpanel.dto.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ProfileErrorStat} cross-instance merge + ranking key. */
class ProfileErrorStatTest {

    @Test
    void combineSumsAllCountsAndKeepsLatestTimestamp() {
        ProfileErrorStat a =
                ProfileErrorStat.builder()
                        .profileAdsPowerId("p1")
                        .failed(3)
                        .profileFailed(2)
                        .loggedOut(1)
                        .transientAction(10)
                        .totalActions(20)
                        .lastAt("2026-06-19T10:00:00Z")
                        .build();
        ProfileErrorStat b =
                ProfileErrorStat.builder()
                        .profileAdsPowerId("p1")
                        .failed(1)
                        .profileFailed(4)
                        .loggedOut(2)
                        .transientAction(5)
                        .totalActions(10)
                        .lastAt("2026-06-19T12:00:00Z")
                        .build();

        ProfileErrorStat merged = a.combine(b);

        assertThat(merged.getProfileAdsPowerId()).isEqualTo("p1");
        assertThat(merged.getFailed()).isEqualTo(4);
        assertThat(merged.getProfileFailed()).isEqualTo(6);
        assertThat(merged.getLoggedOut())
                .isEqualTo(3); // both instances summed — not silently dropped
        assertThat(merged.getTransientAction()).isEqualTo(15);
        assertThat(merged.getTotalActions()).isEqualTo(30);
        // failed + profileFailed + transientAction; loggedOut is a subset of profileFailed →
        // excluded
        assertThat(merged.errorCount()).isEqualTo(25);
        assertThat(merged.getLastAt()).isEqualTo("2026-06-19T12:00:00Z"); // latest wins
    }

    @Test
    void combineWithNullReturnsSelf() {
        ProfileErrorStat a = ProfileErrorStat.builder().profileAdsPowerId("p1").failed(2).build();
        assertThat(a.combine(null)).isSameAs(a);
    }

    @Test
    void errorCountSumsFailedProfileFailedTransientButNotLoggedOut() {
        // loggedOut is a labelled SUBSET of profileFailed; adding it here would double-count.
        ProfileErrorStat s =
                ProfileErrorStat.builder()
                        .failed(7)
                        .profileFailed(5)
                        .loggedOut(4) // subset of the 5 profileFailed — must NOT be added again
                        .transientAction(9)
                        .totalActions(40)
                        .build();
        assertThat(s.errorCount()).isEqualTo(21); // 7 + 5 + 9
    }
}

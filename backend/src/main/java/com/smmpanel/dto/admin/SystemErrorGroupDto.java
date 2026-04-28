package com.smmpanel.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of the Errors tab. ERROR-level log entries from the last N hours, bucketed by a
 * normalized SHA-1 of the message + throwable class so that the same exception with different
 * variable parts collapses into one group.
 *
 * <p>{@link #samples} carries up to a handful of full payloads from the most recent occurrences so
 * the UI can expand a row to show real stack traces without a separate API call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemErrorGroupDto {

    /** First 10 chars of SHA-1(normalized(msg) + ":" + throwableClass). Stable across restarts. */
    private String hash;

    /** A representative message (the most recent occurrence, untrimmed). */
    private String sample;

    /** Throwable class for the group (may be null if the group has no throwable). */
    private String throwableClass;

    /** How many ERROR entries fall into this bucket within the requested window. */
    private long count;

    /** ISO-8601 UTC timestamp of the oldest entry in the window for this group. */
    private String firstSeen;

    /** ISO-8601 UTC timestamp of the newest entry. */
    private String lastSeen;

    /** Distinct logger names (short form) that produced this error. */
    private List<String> sources;

    /** Up to 5 most recent full entries — used by the UI's expand-to-trace view. */
    private List<SystemLogEntryDto> samples;
}

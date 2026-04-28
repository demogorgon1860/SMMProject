package com.smmpanel.dto.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One log line as serialized into Redis by {@link com.smmpanel.monitoring.LogbackRedisAppender} and
 * read back by {@link com.smmpanel.service.admin.SystemLogService}.
 *
 * <p>Field shape is mirrored on the wire — the appender writes JSON with these exact keys, and we
 * deserialize back into the same DTO before the API layer re-serializes for the client. That keeps
 * the contract single-sourced.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SystemLogEntryDto {

    /** ISO-8601 UTC timestamp. */
    private String ts;

    /** TRACE / DEBUG / INFO / WARN / ERROR. */
    private String level;

    /** Short class name (no package), shown in the UI source column. */
    private String source;

    /** Full logger name (with package). Useful for filtering. */
    private String logger;

    /** Originating thread. */
    private String thread;

    /** Formatted message body. May be very long for stack-derived warnings. */
    private String msg;

    /** Stringified throwable (when present). Bounded to avoid Redis OOM. */
    private String throwable;

    /** Class name of the throwable for grouping/filtering. */
    private String throwableClass;

    /** Selected MDC keys. Sensitive keys are stripped at the appender. */
    private Map<String, String> mdc;
}

package com.smmpanel.service.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.admin.SystemErrorGroupDto;
import com.smmpanel.dto.admin.SystemLogEntryDto;
import com.smmpanel.monitoring.RedisLogSink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads structured log entries that {@link com.smmpanel.monitoring.LogbackRedisAppender} pushed to
 * Redis. Powers the {@code /admin/system} Logs and Errors tabs.
 *
 * <p>All reads are bounded — there is a hard cap on how many entries we ever read from the LIST
 * (the appender caps it too, but we cap again as a defense). Failures bubble up as exceptions; the
 * controller turns them into 503 so the UI can render a "Source unreachable" empty state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemLogService {

    /** Hard cap on entries returned by either getRecent() or scanned by getErrorsGrouped(). */
    private static final int MAX_FETCH = 500;

    /**
     * Patterns used to normalize log messages before hashing. Strip the variable parts (numbers,
     * UUIDs, hex IDs, timestamps, URLs) so the same exception with different IDs collapses into a
     * single bucket on the Errors tab.
     */
    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private static final Pattern HEX_ID_PATTERN = Pattern.compile("\\b[0-9a-f]{16,}\\b");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\"<>]+");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Most recent {@code limit} entries (newest first), optionally filtered. Filters apply
     * post-fetch so we always sample from the same bounded window — Redis LIST doesn't support
     * server-side filtering.
     *
     * @param level one of TRACE/DEBUG/INFO/WARN/ERROR, case-insensitive; null = no filter.
     * @param search case-insensitive substring across {@code msg}, {@code source}, {@code logger};
     *     null = no filter.
     * @param source short class-name match against {@code source}; null = no filter.
     * @param limit returned size cap (capped to {@link #MAX_FETCH}).
     */
    public List<SystemLogEntryDto> getRecent(
            String level, String search, String source, int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_FETCH));
        // Always scan up to MAX_FETCH so filters have enough material to work with — otherwise a
        // narrow filter on a tiny limit window would frequently return zero rows.
        List<SystemLogEntryDto> all = readList(MAX_FETCH);
        String levelUp = level == null || level.isBlank() ? null : level.toUpperCase(Locale.ROOT);
        String searchLow =
                search == null || search.isBlank() ? null : search.toLowerCase(Locale.ROOT);
        String sourceLow =
                source == null || source.isBlank() ? null : source.toLowerCase(Locale.ROOT);

        List<SystemLogEntryDto> out = new ArrayList<>(Math.min(all.size(), capped));
        for (SystemLogEntryDto e : all) {
            if (!matchesLevel(e, levelUp)) continue;
            if (!matchesSource(e, sourceLow)) continue;
            if (!matchesSearch(e, searchLow)) continue;
            out.add(e);
            if (out.size() >= capped) break;
        }
        return out;
    }

    /**
     * Group ERROR-level entries from the last {@code sinceHours} hours by normalized message hash.
     * Newest groups first.
     */
    public List<SystemErrorGroupDto> getErrorsGrouped(int sinceHours) {
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(1, sinceHours) * 3_600_000L;
        List<SystemLogEntryDto> all = readList(MAX_FETCH);

        Map<String, GroupAccumulator> groups = new HashMap<>();
        for (SystemLogEntryDto e : all) {
            if (e == null || !"ERROR".equalsIgnoreCase(e.getLevel())) continue;
            long ts = parseTsMillis(e.getTs());
            if (ts > 0 && ts < cutoff) continue;
            String hash = hashGroup(e);
            GroupAccumulator acc = groups.computeIfAbsent(hash, h -> new GroupAccumulator(h));
            acc.add(e);
        }

        List<SystemErrorGroupDto> result = new ArrayList<>(groups.size());
        for (GroupAccumulator g : groups.values()) {
            result.add(g.toDto());
        }
        // Most recent group first; tie-break on count desc to bubble up loud errors.
        // nullsLast guards against groups whose entries all lacked a timestamp — without it the
        // comparator would NPE inside reverseOrder() the moment a null lastSeen showed up.
        result.sort(
                Comparator.comparing(
                                SystemErrorGroupDto::getLastSeen,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SystemErrorGroupDto::getCount, Comparator.reverseOrder()));
        return result;
    }

    /** Lightweight count of distinct ERROR-level entries within the window. Powers a tab badge. */
    public long countErrors(int sinceHours) {
        long cutoff = System.currentTimeMillis() - Math.max(1, sinceHours) * 3_600_000L;
        long count = 0;
        for (SystemLogEntryDto e : readList(MAX_FETCH)) {
            if (!"ERROR".equalsIgnoreCase(e.getLevel())) continue;
            long ts = parseTsMillis(e.getTs());
            if (ts > 0 && ts < cutoff) continue;
            count++;
        }
        return count;
    }

    /** Fetch and deserialize up to {@code limit} entries from the underlying Redis LIST. */
    private List<SystemLogEntryDto> readList(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_FETCH));
        List<String> raw =
                stringRedisTemplate.opsForList().range(RedisLogSink.LIST_KEY, 0, capped - 1);
        if (raw == null || raw.isEmpty()) return List.of();
        List<SystemLogEntryDto> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                out.add(objectMapper.readValue(s, SystemLogEntryDto.class));
            } catch (Exception ex) {
                // One corrupt JSON row mustn't kill the whole tab. Skip it. Don't log via SLF4J —
                // we'd write back into the same LIST and possibly amplify the corruption.
                System.err.println(
                        "[SystemLogService] dropping corrupt log entry: " + ex.getMessage());
            }
        }
        return out;
    }

    private static boolean matchesLevel(SystemLogEntryDto e, String levelUp) {
        if (levelUp == null) return true;
        return levelUp.equalsIgnoreCase(e.getLevel());
    }

    private static boolean matchesSource(SystemLogEntryDto e, String sourceLow) {
        if (sourceLow == null) return true;
        String s = e.getSource();
        return s != null && s.toLowerCase(Locale.ROOT).contains(sourceLow);
    }

    private static boolean matchesSearch(SystemLogEntryDto e, String searchLow) {
        if (searchLow == null) return true;
        if (containsLower(e.getMsg(), searchLow)) return true;
        if (containsLower(e.getSource(), searchLow)) return true;
        if (containsLower(e.getLogger(), searchLow)) return true;
        if (containsLower(e.getThrowable(), searchLow)) return true;
        return false;
    }

    private static boolean containsLower(String haystack, String needleLow) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLow);
    }

    /** SHA-1 of normalized(msg) + ":" + throwableClass, truncated to 10 hex chars for display. */
    private static String hashGroup(SystemLogEntryDto e) {
        String msg = e.getMsg() == null ? "" : e.getMsg();
        String tc = e.getThrowableClass() == null ? "" : e.getThrowableClass();
        String key = normalize(msg) + ":" + tc;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 10);
        } catch (NoSuchAlgorithmException nsa) {
            // SHA-1 is part of the standard JDK distribution — should never happen.
            return Integer.toHexString(key.hashCode());
        }
    }

    /** Replace high-cardinality fragments so equivalent messages collapse to one bucket. */
    private static String normalize(String msg) {
        String s = UUID_PATTERN.matcher(msg).replaceAll("UUID");
        s = URL_PATTERN.matcher(s).replaceAll("URL");
        s = HEX_ID_PATTERN.matcher(s).replaceAll("HEX");
        s = NUMBER_PATTERN.matcher(s).replaceAll("N");
        return s;
    }

    private static long parseTsMillis(String ts) {
        if (ts == null || ts.isBlank()) return 0L;
        try {
            return Instant.parse(ts).toEpochMilli();
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** Mutable accumulator used during the group-by pass in {@link #getErrorsGrouped}. */
    private static final class GroupAccumulator {
        // Bound the response payload — each sample can carry a 16KB throwable, so we'd ship up to
        // groups×samples×16KB if this were unbounded. 3 samples per group keeps the worst case
        // around a few hundred KB while still letting the UI show a couple of stack traces.
        private static final int MAX_SAMPLES = 3;

        private final String hash;
        private long count;
        private SystemLogEntryDto sampleEntry;
        private String firstSeen;
        private String lastSeen;
        private final LinkedHashSet<String> sources = new LinkedHashSet<>();
        private final List<SystemLogEntryDto> samples = new ArrayList<>(MAX_SAMPLES);

        GroupAccumulator(String hash) {
            this.hash = hash;
        }

        void add(SystemLogEntryDto e) {
            count++;
            String ts = e.getTs();
            if (ts != null && !ts.isBlank()) {
                if (lastSeen == null || ts.compareTo(lastSeen) > 0) lastSeen = ts;
                if (firstSeen == null || ts.compareTo(firstSeen) < 0) firstSeen = ts;
            }
            if (e.getSource() != null) sources.add(e.getSource());
            if (sampleEntry == null
                    || (e.getTs() != null
                            && sampleEntry.getTs() != null
                            && e.getTs().compareTo(sampleEntry.getTs()) > 0)) {
                sampleEntry = e;
            }
            if (samples.size() < MAX_SAMPLES) {
                samples.add(e);
            }
        }

        SystemErrorGroupDto toDto() {
            return SystemErrorGroupDto.builder()
                    .hash(hash)
                    .sample(sampleEntry == null ? null : sampleEntry.getMsg())
                    .throwableClass(sampleEntry == null ? null : sampleEntry.getThrowableClass())
                    .count(count)
                    .firstSeen(firstSeen)
                    .lastSeen(lastSeen)
                    .sources(new ArrayList<>(sources))
                    .samples(samples)
                    .build();
        }
    }
}

package com.smmpanel.scheduler;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps Postgres partitions for time-partitioned tables ahead of "now". Without this, an INSERT
 * past the latest partition's upper bound throws {@code 23514 no partition of relation found} and
 * the entire request fails — silent until the bound is crossed, painful when it is.
 *
 * <p>The Liquibase migrations pre-create monthly partitions through {@code 2026-12} for {@code
 * orders} and {@code operator_logs}. After that date this scheduler is the only thing standing
 * between the panel and a 500. Defaults to keeping 6 months of forward-buffer; runs on boot
 * (catches a long-stopped container picking up after the cliff) and daily at 01:30 (quiet window
 * between cleanup jobs at :00 and EU traffic ramp-up).
 *
 * <p>Idempotency: {@code CREATE TABLE IF NOT EXISTS ... PARTITION OF} no-ops on existing
 * partitions, so the daily run is cheap. The bound check is in DDL; we don't need to query
 * pg_inherits first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionMaintenanceScheduler {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Tables and the column they're partitioned on. {@code created_at} for both today; the column
     * name lives here only for documentation — the DDL doesn't need it (PARTITION OF inherits the
     * parent's partition key).
     */
    private static final List<String> PARTITIONED_TABLES = List.of("orders", "operator_logs");

    @Value("${app.partition-maintenance.months-ahead:6}")
    private int monthsAhead;

    @Value("${app.partition-maintenance.enabled:true}")
    private boolean enabled;

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final DateTimeFormatter BOUND = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Run on startup so a fresh container always has the next N months staged. */
    @PostConstruct
    void onStart() {
        if (!enabled) {
            log.info("Partition maintenance disabled by config — skipping startup sweep");
            return;
        }
        ensureFuturePartitions();
    }

    /**
     * Daily at 01:30. Cron picked to sit between AccountDeletionScheduler (03:00) and refresh token
     * cleanup (02:00) — runs in the quiet window with minimal contention.
     */
    @Scheduled(cron = "${app.partition-maintenance.cron:0 30 1 * * *}")
    public void scheduledRun() {
        if (!enabled) return;
        ensureFuturePartitions();
    }

    void ensureFuturePartitions() {
        LocalDate today = LocalDate.now().withDayOfMonth(1);
        for (int offset = 0; offset <= monthsAhead; offset++) {
            LocalDate periodStart = today.plusMonths(offset);
            LocalDate periodEnd = periodStart.plusMonths(1);
            for (String table : PARTITIONED_TABLES) {
                createPartitionIfMissing(table, periodStart, periodEnd);
            }
        }
    }

    private void createPartitionIfMissing(String parentTable, LocalDate from, LocalDate to) {
        // CREATE TABLE IF NOT EXISTS is idempotent → daily reruns are no-ops.
        // Both inputs are framework-controlled (whitelisted parent table names + locally-derived
        // dates from LocalDate); no user input → no SQL injection vector.
        String partitionName = parentTable + "_" + from.format(SUFFIX);
        String ddl =
                String.format(
                        "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s "
                                + "FOR VALUES FROM ('%s') TO ('%s')",
                        partitionName, parentTable, from.format(BOUND), to.format(BOUND));
        try {
            jdbcTemplate.execute(ddl);
            log.debug("Ensured partition {} ({} → {})", partitionName, from, to);
        } catch (Exception e) {
            // A failure on one partition (e.g. a manual partition with the same name and a
            // different bound) shouldn't poison the others; log and continue. The mismatch
            // will repeat tomorrow until an operator notices.
            log.error(
                    "Failed to ensure partition {} on {}: {}",
                    partitionName,
                    parentTable,
                    e.toString());
        }
    }
}

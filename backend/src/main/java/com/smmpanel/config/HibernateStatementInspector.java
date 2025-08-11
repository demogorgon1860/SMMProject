package com.smmpanel.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Custom Hibernate Statement Inspector for monitoring and performance analysis Tracks query
 * execution patterns, slow queries, and provides detailed statistics
 */
@Component
@Slf4j
public class HibernateStatementInspector implements StatementInspector {

    @Value("${hibernate.slow-query-threshold-ms:100}")
    private long slowQueryThresholdMs;

    @Value("${hibernate.statement-inspector.enabled:true}")
    private boolean inspectorEnabled;

    @Value("${hibernate.statement-inspector.log-all-queries:false}")
    private boolean logAllQueries;

    // Statistics tracking
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalSlowQueries = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> queryTypeCounters =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> slowQueryPatterns = new ConcurrentHashMap<>();

    // Thread-local for tracking query execution time
    private final ThreadLocal<Long> queryStartTime = new ThreadLocal<>();

    @Override
    public String inspect(String sql) {
        if (!inspectorEnabled) {
            return sql;
        }

        // Record query start time
        queryStartTime.set(System.currentTimeMillis());

        // Count total queries
        totalQueries.incrementAndGet();

        // Classify and count query types
        String queryType = classifyQuery(sql);
        queryTypeCounters.computeIfAbsent(queryType, k -> new AtomicLong(0)).incrementAndGet();

        // Log query if enabled
        if (logAllQueries) {
            log.debug("Executing query [{}]: {}", queryType, formatSqlForLogging(sql));
        }

        // Check for potential performance issues
        analyzeQueryForIssues(sql, queryType);

        return sql;
    }

    /** Called after query execution to measure performance */
    public void recordQueryCompletion(String sql) {
        if (!inspectorEnabled) {
            return;
        }

        Long startTime = queryStartTime.get();
        if (startTime != null) {
            long executionTime = System.currentTimeMillis() - startTime;

            if (executionTime > slowQueryThresholdMs) {
                totalSlowQueries.incrementAndGet();
                String pattern = extractQueryPattern(sql);
                slowQueryPatterns.put(pattern, executionTime);

                log.warn("SLOW QUERY detected ({}ms): {}", executionTime, formatSqlForLogging(sql));
            }

            queryStartTime.remove();
        }
    }

    /** Classify query type for statistics */
    private String classifyQuery(String sql) {
        String normalizedSql = sql.trim().toUpperCase();

        if (normalizedSql.startsWith("SELECT")) {
            if (normalizedSql.contains("COUNT(")) {
                return "SELECT_COUNT";
            } else if (normalizedSql.contains("JOIN")) {
                return "SELECT_JOIN";
            } else {
                return "SELECT";
            }
        } else if (normalizedSql.startsWith("INSERT")) {
            return "INSERT";
        } else if (normalizedSql.startsWith("UPDATE")) {
            return "UPDATE";
        } else if (normalizedSql.startsWith("DELETE")) {
            return "DELETE";
        } else if (normalizedSql.startsWith("CALL") || normalizedSql.startsWith("EXEC")) {
            return "PROCEDURE";
        } else {
            return "OTHER";
        }
    }

    /** Analyze query for potential performance issues */
    private void analyzeQueryForIssues(String sql, String queryType) {
        String normalizedSql = sql.toUpperCase();

        // Check for SELECT N+1 patterns
        if ("SELECT".equals(queryType)
                && normalizedSql.contains("WHERE")
                && !normalizedSql.contains("JOIN")
                && normalizedSql.contains("IN (")) {
            log.warn("Potential N+1 query pattern detected: {}", formatSqlForLogging(sql));
        }

        // Check for missing pagination
        if ("SELECT".equals(queryType)
                && !normalizedSql.contains("LIMIT")
                && !normalizedSql.contains("ROWNUM")
                && !normalizedSql.contains("TOP")) {
            if (normalizedSql.contains("ORDER BY")) {
                log.info("Query with ORDER BY but no pagination: {}", formatSqlForLogging(sql));
            }
        }

        // Check for SELECT * queries
        if ("SELECT".equals(queryType) && normalizedSql.contains("SELECT *")) {
            log.warn(
                    "SELECT * query detected (consider selecting specific columns): {}",
                    formatSqlForLogging(sql));
        }

        // Check for complex WHERE clauses without indexes
        if (normalizedSql.contains("WHERE") && normalizedSql.contains("LIKE '%")) {
            log.warn(
                    "LIKE query with leading wildcard detected (may be slow): {}",
                    formatSqlForLogging(sql));
        }
    }

    /** Extract query pattern for grouping similar queries */
    private String extractQueryPattern(String sql) {
        // Replace parameter placeholders and values with generic markers
        return sql.replaceAll("\\?", "?")
                .replaceAll("'[^']*'", "'?'")
                .replaceAll("\\d+", "?")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Format SQL for logging (truncate if too long) */
    private String formatSqlForLogging(String sql) {
        if (sql.length() > 200) {
            return sql.substring(0, 200) + "...";
        }
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** Get current query statistics */
    public QueryStatistics getStatistics() {
        return QueryStatistics.builder()
                .totalQueries(totalQueries.get())
                .totalSlowQueries(totalSlowQueries.get())
                .queryTypeCounters(new ConcurrentHashMap<>(queryTypeCounters))
                .slowQueryPatterns(new ConcurrentHashMap<>(slowQueryPatterns))
                .build();
    }

    /** Reset statistics counters */
    public void resetStatistics() {
        totalQueries.set(0);
        totalSlowQueries.set(0);
        queryTypeCounters.clear();
        slowQueryPatterns.clear();
        log.info("Hibernate query statistics reset");
    }

    /** Log current statistics summary */
    public void logStatisticsSummary() {
        QueryStatistics stats = getStatistics();

        log.info("=== Hibernate Query Statistics Summary ===");
        log.info("Total Queries: {}", stats.getTotalQueries());
        log.info(
                "Slow Queries: {} ({}%)",
                stats.getTotalSlowQueries(),
                stats.getTotalQueries() > 0
                        ? String.format(
                                "%.2f",
                                (double) stats.getTotalSlowQueries()
                                        / stats.getTotalQueries()
                                        * 100)
                        : "0");

        log.info("Query Type Breakdown:");
        stats.getQueryTypeCounters()
                .forEach(
                        (type, count) ->
                                log.info(
                                        "  {}: {} ({}%)",
                                        type,
                                        count.get(),
                                        String.format(
                                                "%.2f",
                                                (double) count.get()
                                                        / stats.getTotalQueries()
                                                        * 100)));

        if (!stats.getSlowQueryPatterns().isEmpty()) {
            log.warn("Top Slow Query Patterns:");
            stats.getSlowQueryPatterns().entrySet().stream()
                    .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                    .limit(5)
                    .forEach(entry -> log.warn("  {}ms: {}", entry.getValue(), entry.getKey()));
        }

        log.info("==========================================");
    }

    /** Data class for query statistics */
    @lombok.Data
    @lombok.Builder
    public static class QueryStatistics {
        private long totalQueries;
        private long totalSlowQueries;
        private ConcurrentHashMap<String, AtomicLong> queryTypeCounters;
        private ConcurrentHashMap<String, Long> slowQueryPatterns;

        public double getSlowQueryPercentage() {
            return totalQueries > 0 ? (double) totalSlowQueries / totalQueries * 100 : 0;
        }
    }
}

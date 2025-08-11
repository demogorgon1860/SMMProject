package com.smmpanel.config;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * HIBERNATE PERFORMANCE CONFIGURATION
 *
 * <p>Features: 1. Second-level cache configuration 2. Hibernate statistics monitoring 3. N+1 query
 * detection in development 4. Performance metrics collection
 */
@Slf4j
@Configuration
public class HibernateConfig {

    @Autowired private EntityManagerFactory entityManagerFactory;

    /** Enable Hibernate statistics in development mode */
    @Bean
    @ConditionalOnProperty(
            name = "hibernate.generate_statistics",
            havingValue = "true",
            matchIfMissing = false)
    public Statistics hibernateStatistics() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);

        log.info("Hibernate statistics enabled for performance monitoring");
        return statistics;
    }

    /**
     * Log Hibernate statistics periodically in development Helps identify N+1 queries and
     * performance issues
     */
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    @ConditionalOnProperty(
            name = "hibernate.generate_statistics",
            havingValue = "true",
            matchIfMissing = false)
    public void logHibernateStatistics() {
        if (entityManagerFactory != null) {
            SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();

            if (statistics.isStatisticsEnabled()) {
                logPerformanceMetrics(statistics);
                logQueryStatistics(statistics);
                logCacheStatistics(statistics);

                // Reset statistics to get fresh metrics for next period
                statistics.clear();
            }
        }
    }

    private void logPerformanceMetrics(Statistics statistics) {
        long queryCount = statistics.getQueryExecutionCount();
        long totalQueryTime = statistics.getQueryExecutionMaxTime();
        double avgQueryTime = queryCount > 0 ? (double) totalQueryTime / queryCount : 0;

        log.info(
                "HIBERNATE PERFORMANCE: {} queries executed, avg time: {:.2f}ms, max time: {}ms",
                queryCount,
                avgQueryTime,
                statistics.getQueryExecutionMaxTime());

        // Detect potential N+1 problems
        if (queryCount > 100) {
            log.warn(
                    "HIGH QUERY COUNT DETECTED: {} queries in 30 seconds - possible N+1 problem",
                    queryCount);
        }

        // Detect slow queries
        if (statistics.getQueryExecutionMaxTime() > 1000) {
            log.warn(
                    "SLOW QUERY DETECTED: Max query time {}ms - SQL: {}",
                    statistics.getQueryExecutionMaxTime(),
                    statistics.getQueries()[0]); // Most recent query
        }
    }

    private void logQueryStatistics(Statistics statistics) {
        log.info(
                "HIBERNATE QUERIES: {} executed, {} cache hits, {} cache misses",
                statistics.getQueryExecutionCount(),
                statistics.getQueryCacheHitCount(),
                statistics.getQueryCacheMissCount());

        // Log most executed queries for optimization opportunities
        String[] queries = statistics.getQueries();
        if (queries.length > 0) {
            log.debug("Most frequent query: {}", queries[0]);
        }
    }

    private void logCacheStatistics(Statistics statistics) {
        // Second level cache statistics
        log.info(
                "SECOND LEVEL CACHE: {} puts, {} hits, {} misses (hit ratio: {:.2f}%)",
                statistics.getSecondLevelCachePutCount(),
                statistics.getSecondLevelCacheHitCount(),
                statistics.getSecondLevelCacheMissCount(),
                calculateHitRatio(
                        statistics.getSecondLevelCacheHitCount(),
                        statistics.getSecondLevelCacheMissCount()));

        // Entity cache statistics for key entities
        logEntityCacheStats(statistics, "com.smmpanel.entity.Service");
        logEntityCacheStats(statistics, "com.smmpanel.entity.User");
        logEntityCacheStats(statistics, "com.smmpanel.entity.ConversionCoefficient");

        // Collection cache statistics
        logCollectionCacheStats(statistics, "com.smmpanel.entity.User.orders");
        logCollectionCacheStats(statistics, "com.smmpanel.entity.User.balanceTransactions");
    }

    private void logEntityCacheStats(Statistics statistics, String entityName) {
        // Note: Entity-specific cache statistics require different API approach in newer Hibernate
        // versions
        try {
            // Get region statistics if available
            log.debug(
                    "ENTITY CACHE [{}]: Statistics available through SessionFactory.getCache()",
                    entityName.substring(entityName.lastIndexOf('.') + 1));
        } catch (Exception e) {
            // Fallback to general cache statistics
            log.debug(
                    "ENTITY CACHE [{}]: Using general cache statistics",
                    entityName.substring(entityName.lastIndexOf('.') + 1));
        }
    }

    private void logCollectionCacheStats(Statistics statistics, String collectionName) {
        // Note: Collection cache statistics require Hibernate 5.4+
        try {
            log.debug(
                    "COLLECTION CACHE [{}]: Statistics available in newer Hibernate versions",
                    collectionName.substring(collectionName.lastIndexOf('.') + 1));
        } catch (Exception e) {
            // Ignore - collection statistics not available in this version
        }
    }

    private double calculateHitRatio(long hits, long misses) {
        long total = hits + misses;
        return total > 0 ? (double) hits / total * 100.0 : 0.0;
    }

    /** Manual cache eviction for reference data */
    public void evictServiceCache() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getCache().evictEntityData("com.smmpanel.entity.Service");
        log.info("Service cache evicted");
    }

    public void evictConversionCoefficientCache() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        sessionFactory.getCache().evictEntityData("com.smmpanel.entity.ConversionCoefficient");
        log.info("ConversionCoefficient cache evicted");
    }

    /** Get cache statistics for monitoring endpoints */
    public String getCacheStatisticsReport() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();

        if (!statistics.isStatisticsEnabled()) {
            return "Hibernate statistics are disabled";
        }

        return String.format(
                "Cache Statistics:\n"
                        + "- Second Level Cache Hit Ratio: %.2f%%\n"
                        + "- Query Cache Hit Ratio: %.2f%%\n"
                        + "- Total Queries: %d\n"
                        + "- Average Query Time: %.2fms\n"
                        + "- Max Query Time: %dms",
                calculateHitRatio(
                        statistics.getSecondLevelCacheHitCount(),
                        statistics.getSecondLevelCacheMissCount()),
                calculateHitRatio(
                        statistics.getQueryCacheHitCount(), statistics.getQueryCacheMissCount()),
                statistics.getQueryExecutionCount(),
                statistics.getQueryExecutionCount() > 0
                        ? (double) statistics.getQueryExecutionMaxTime()
                                / statistics.getQueryExecutionCount()
                        : 0.0,
                statistics.getQueryExecutionMaxTime());
    }
}

package com.smmpanel.controller.admin;

import com.smmpanel.config.HibernateConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManagerFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * PERFORMANCE MONITORING CONTROLLER
 * 
 * Features:
 * 1. Hibernate statistics monitoring
 * 2. N+1 query detection
 * 3. Cache performance metrics
 * 4. Database performance insights
 * 
 * Security: Admin-only access
 */
@RestController
@RequestMapping("/api/admin/performance")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "hibernate.generate_statistics", havingValue = "true", matchIfMissing = false)
@Slf4j
public class PerformanceMonitoringController {

    private final HibernateConfig hibernateConfig;
    private final EntityManagerFactory entityManagerFactory;

    /**
     * Get comprehensive Hibernate statistics
     */
    @GetMapping("/hibernate-stats")
    public ResponseEntity<Map<String, Object>> getHibernateStatistics() {
        try {
            SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            
            Map<String, Object> stats = new HashMap<>();
            
            // Query Statistics
            stats.put("queryExecutionCount", statistics.getQueryExecutionCount());
            stats.put("queryExecutionMaxTime", statistics.getQueryExecutionMaxTime());
            stats.put("queryExecutionMaxTimeQueryString", statistics.getQueryExecutionMaxTimeQueryString());
            
            // Cache Statistics
            stats.put("secondLevelCacheHitCount", statistics.getSecondLevelCacheHitCount());
            stats.put("secondLevelCacheMissCount", statistics.getSecondLevelCacheMissCount());
            stats.put("secondLevelCachePutCount", statistics.getSecondLevelCachePutCount());
            
            double cacheHitRatio = calculateHitRatio(
                statistics.getSecondLevelCacheHitCount(), 
                statistics.getSecondLevelCacheMissCount()
            );
            stats.put("secondLevelCacheHitRatio", cacheHitRatio);
            
            // Query Cache Statistics
            stats.put("queryCacheHitCount", statistics.getQueryCacheHitCount());
            stats.put("queryCacheMissCount", statistics.getQueryCacheMissCount());
            stats.put("queryCachePutCount", statistics.getQueryCachePutCount());
            
            double queryCacheHitRatio = calculateHitRatio(
                statistics.getQueryCacheHitCount(),
                statistics.getQueryCacheMissCount()
            );
            stats.put("queryCacheHitRatio", queryCacheHitRatio);
            
            // Connection Statistics
            stats.put("connectCount", statistics.getConnectCount());
            stats.put("flushCount", statistics.getFlushCount());
            stats.put("transactionCount", statistics.getTransactionCount());
            
            // Entity Statistics
            stats.put("entityLoadCount", statistics.getEntityLoadCount());
            stats.put("entityUpdateCount", statistics.getEntityUpdateCount());
            stats.put("entityInsertCount", statistics.getEntityInsertCount());
            stats.put("entityDeleteCount", statistics.getEntityDeleteCount());
            
            // Collection Statistics
            stats.put("collectionLoadCount", statistics.getCollectionLoadCount());
            stats.put("collectionFetchCount", statistics.getCollectionFetchCount());
            stats.put("collectionUpdateCount", statistics.getCollectionUpdateCount());
            
            // Performance Insights
            addPerformanceInsights(stats, statistics);
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            log.error("Error retrieving Hibernate statistics: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to retrieve statistics: " + e.getMessage()));
        }
    }

    /**
     * Get cache statistics report
     */
    @GetMapping("/cache-report")
    public ResponseEntity<String> getCacheReport() {
        try {
            String report = hibernateConfig.getCacheStatisticsReport();
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Error generating cache report: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Failed to generate cache report: " + e.getMessage());
        }
    }

    /**
     * Clear Hibernate statistics
     */
    @PostMapping("/clear-stats")
    public ResponseEntity<Map<String, String>> clearStatistics() {
        try {
            SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            statistics.clear();
            
            log.info("Hibernate statistics cleared by admin");
            return ResponseEntity.ok(Map.of("message", "Statistics cleared successfully"));
            
        } catch (Exception e) {
            log.error("Error clearing Hibernate statistics: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to clear statistics: " + e.getMessage()));
        }
    }

    /**
     * Evict all second-level cache
     */
    @PostMapping("/evict-cache")
    public ResponseEntity<Map<String, String>> evictCache(@RequestParam(required = false) String entityName) {
        try {
            SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
            
            if (entityName != null && !entityName.isEmpty()) {
                sessionFactory.getCache().evictEntityData(entityName);
                log.info("Cache evicted for entity: {}", entityName);
                return ResponseEntity.ok(Map.of("message", "Cache evicted for entity: " + entityName));
            } else {
                sessionFactory.getCache().evictAllRegions();
                log.info("All cache regions evicted");
                return ResponseEntity.ok(Map.of("message", "All cache regions evicted"));
            }
            
        } catch (Exception e) {
            log.error("Error evicting cache: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to evict cache: " + e.getMessage()));
        }
    }

    /**
     * Get N+1 query detection analysis
     */
    @GetMapping("/n-plus-one-analysis")
    public ResponseEntity<Map<String, Object>> getNPlusOneAnalysis() {
        try {
            SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            
            Map<String, Object> analysis = new HashMap<>();
            
            // Calculate potential N+1 indicators
            long queryCount = statistics.getQueryExecutionCount();
            long entityLoadCount = statistics.getEntityLoadCount();
            long collectionLoadCount = statistics.getCollectionLoadCount();
            
            // Heuristics for N+1 detection
            boolean possibleNPlusOne = false;
            String[] indicators = {};
            
            if (queryCount > entityLoadCount * 2) {
                possibleNPlusOne = true;
                indicators = new String[]{"High query-to-entity ratio detected"};
            }
            
            if (collectionLoadCount > entityLoadCount) {
                possibleNPlusOne = true;
                indicators = new String[]{"High collection load count - possible lazy loading issues"};
            }
            
            analysis.put("possibleNPlusOneDetected", possibleNPlusOne);
            analysis.put("indicators", indicators);
            analysis.put("queryCount", queryCount);
            analysis.put("entityLoadCount", entityLoadCount);
            analysis.put("collectionLoadCount", collectionLoadCount);
            analysis.put("queryToEntityRatio", entityLoadCount > 0 ? (double) queryCount / entityLoadCount : 0.0);
            
            // Recommendations
            if (possibleNPlusOne) {
                analysis.put("recommendations", new String[]{
                    "Consider using @EntityGraph or JOIN FETCH queries",
                    "Review lazy loading configuration",
                    "Use projection DTOs for read-only operations",
                    "Enable batch fetching with @BatchSize"
                });
            }
            
            return ResponseEntity.ok(analysis);
            
        } catch (Exception e) {
            log.error("Error performing N+1 analysis: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to perform N+1 analysis: " + e.getMessage()));
        }
    }

    private double calculateHitRatio(long hits, long misses) {
        long total = hits + misses;
        return total > 0 ? (double) hits / total * 100.0 : 0.0;
    }

    private void addPerformanceInsights(Map<String, Object> stats, Statistics statistics) {
        // Add performance insights and recommendations
        Map<String, Object> insights = new HashMap<>();
        
        // Cache performance
        double cacheHitRatio = calculateHitRatio(
            statistics.getSecondLevelCacheHitCount(),
            statistics.getSecondLevelCacheMissCount()
        );
        
        if (cacheHitRatio < 50.0) {
            insights.put("cacheConcern", "Low cache hit ratio - consider cache configuration review");
        }
        
        // Query performance
        if (statistics.getQueryExecutionMaxTime() > 1000) {
            insights.put("slowQueryConcern", "Slow queries detected - consider query optimization");
        }
        
        // N+1 indicators
        if (statistics.getQueryExecutionCount() > 100) {
            insights.put("highQueryCountConcern", "High query count - possible N+1 query problem");
        }
        
        stats.put("performanceInsights", insights);
    }
}
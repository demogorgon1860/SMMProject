package com.smmpanel.controller;

import com.smmpanel.config.HibernateOptimizationConfig.HibernateCacheStatistics;
import com.smmpanel.config.HibernateStatementInspector;
import com.smmpanel.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controller for monitoring Hibernate performance and cache statistics
 * Provides endpoints for administrators to monitor database performance
 */
@RestController
@RequestMapping("/api/v1/admin/hibernate")
@RequiredArgsConstructor
@Tag(name = "Hibernate Monitoring", description = "Hibernate performance and cache monitoring endpoints")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
public class HibernateMonitoringController {

    @PersistenceContext
    private EntityManager entityManager;
    
    @Autowired(required = false)
    private HibernateStatementInspector statementInspector;
    
    @Autowired(required = false)
    private HibernateCacheStatistics hibernateCacheStatistics;

    @GetMapping("/statistics")
    @Operation(
        summary = "Get Hibernate Statistics",
        description = "Retrieve comprehensive Hibernate performance statistics"
    )
    public ResponseEntity<ApiResponse<Map<String, Object>>> getHibernateStatistics() {
        try {
            SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                    .unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            
            Map<String, Object> stats = new LinkedHashMap<>();
            
            // Basic statistics
            stats.put("statisticsEnabled", statistics.isStatisticsEnabled());
            stats.put("startTime", statistics.getStartTime());
            
            // Query statistics
            Map<String, Object> queryStats = new LinkedHashMap<>();
            queryStats.put("queryExecutionCount", statistics.getQueryExecutionCount());
            queryStats.put("queryExecutionMaxTime", statistics.getQueryExecutionMaxTime());
            queryStats.put("queryExecutionMaxTimeQueryString", statistics.getQueryExecutionMaxTimeQueryString());
            queryStats.put("slowQueryCount", statistics.getSlowQueryCount());
            stats.put("queryStatistics", queryStats);
            
            // Entity statistics
            Map<String, Object> entityStats = new LinkedHashMap<>();
            entityStats.put("entityLoadCount", statistics.getEntityLoadCount());
            entityStats.put("entityFetchCount", statistics.getEntityFetchCount());
            entityStats.put("entityInsertCount", statistics.getEntityInsertCount());
            entityStats.put("entityUpdateCount", statistics.getEntityUpdateCount());
            entityStats.put("entityDeleteCount", statistics.getEntityDeleteCount());
            stats.put("entityStatistics", entityStats);
            
            // Collection statistics
            Map<String, Object> collectionStats = new LinkedHashMap<>();
            collectionStats.put("collectionLoadCount", statistics.getCollectionLoadCount());
            collectionStats.put("collectionFetchCount", statistics.getCollectionFetchCount());
            collectionStats.put("collectionUpdateCount", statistics.getCollectionUpdateCount());
            collectionStats.put("collectionRemoveCount", statistics.getCollectionRemoveCount());
            collectionStats.put("collectionRecreateCount", statistics.getCollectionRecreateCount());
            stats.put("collectionStatistics", collectionStats);
            
            // Cache statistics
            Map<String, Object> cacheStats = new LinkedHashMap<>();
            cacheStats.put("secondLevelCacheHitCount", statistics.getSecondLevelCacheHitCount());
            cacheStats.put("secondLevelCacheMissCount", statistics.getSecondLevelCacheMissCount());
            cacheStats.put("secondLevelCachePutCount", statistics.getSecondLevelCachePutCount());
            
            long cacheHits = statistics.getSecondLevelCacheHitCount();
            long cacheMisses = statistics.getSecondLevelCacheMissCount();
            if (cacheHits + cacheMisses > 0) {
                double hitRatio = (double) cacheHits / (cacheHits + cacheMisses) * 100;
                cacheStats.put("cacheHitRatio", String.format("%.2f%%", hitRatio));
            } else {
                cacheStats.put("cacheHitRatio", "N/A");
            }
            
            // Query cache statistics
            cacheStats.put("queryCacheHitCount", statistics.getQueryCacheHitCount());
            cacheStats.put("queryCacheMissCount", statistics.getQueryCacheMissCount());
            cacheStats.put("queryCachePutCount", statistics.getQueryCachePutCount());
            
            stats.put("cacheStatistics", cacheStats);
            
            // Session statistics
            Map<String, Object> sessionStats = new LinkedHashMap<>();
            sessionStats.put("sessionOpenCount", statistics.getSessionOpenCount());
            sessionStats.put("sessionCloseCount", statistics.getSessionCloseCount());
            sessionStats.put("flushCount", statistics.getFlushCount());
            sessionStats.put("connectCount", statistics.getConnectCount());
            stats.put("sessionStatistics", sessionStats);
            
            // Transaction statistics
            Map<String, Object> transactionStats = new LinkedHashMap<>();
            transactionStats.put("transactionCount", statistics.getTransactionCount());
            transactionStats.put("successfulTransactionCount", statistics.getSuccessfulTransactionCount());
            transactionStats.put("optimisticFailureCount", statistics.getOptimisticFailureCount());
            stats.put("transactionStatistics", transactionStats);
            
            return ResponseEntity.ok(ApiResponse.success(stats));
            
        } catch (Exception e) {
            log.error("Failed to retrieve Hibernate statistics", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve Hibernate statistics: " + e.getMessage()));
        }
    }

    @GetMapping("/cache-statistics")
    @Operation(
        summary = "Get Cache Statistics",
        description = "Retrieve detailed second-level cache statistics"
    )
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCacheStatistics() {
        if (hibernateCacheStatistics == null) {
            return ResponseEntity.ok(ApiResponse.error("Cache statistics not available"));
        }
        
        try {
            Map<String, Object> stats = hibernateCacheStatistics.getCacheStatistics();
            return ResponseEntity.ok(ApiResponse.success(stats));
            
        } catch (Exception e) {
            log.error("Failed to retrieve cache statistics", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve cache statistics: " + e.getMessage()));
        }
    }

    @GetMapping("/query-inspector")
    @Operation(
        summary = "Get Query Inspector Statistics",
        description = "Retrieve statistics from the custom query inspector"
    )
    public ResponseEntity<ApiResponse<Object>> getQueryInspectorStatistics() {
        if (statementInspector == null) {
            return ResponseEntity.ok(ApiResponse.error("Query inspector not available"));
        }
        
        try {
            HibernateStatementInspector.QueryStatistics stats = statementInspector.getStatistics();
            
            Map<String, Object> inspectorStats = new LinkedHashMap<>();
            inspectorStats.put("totalQueries", stats.getTotalQueries());
            inspectorStats.put("totalSlowQueries", stats.getTotalSlowQueries());
            inspectorStats.put("slowQueryPercentage", String.format("%.2f%%", stats.getSlowQueryPercentage()));
            inspectorStats.put("queryTypeCounters", stats.getQueryTypeCounters());
            inspectorStats.put("slowQueryPatterns", stats.getSlowQueryPatterns());
            
            return ResponseEntity.ok(ApiResponse.success(inspectorStats));
            
        } catch (Exception e) {
            log.error("Failed to retrieve query inspector statistics", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to retrieve query inspector statistics: " + e.getMessage()));
        }
    }

    @PostMapping("/statistics/reset")
    @Operation(
        summary = "Reset Statistics",
        description = "Reset all Hibernate statistics counters"
    )
    public ResponseEntity<ApiResponse<String>> resetStatistics() {
        try {
            SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                    .unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            
            if (statistics.isStatisticsEnabled()) {
                statistics.clear();
                
                if (statementInspector != null) {
                    statementInspector.resetStatistics();
                }
                
                log.info("Hibernate statistics reset by admin request");
                return ResponseEntity.ok(ApiResponse.success("Statistics reset successfully"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("Statistics are not enabled"));
            }
            
        } catch (Exception e) {
            log.error("Failed to reset Hibernate statistics", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to reset statistics: " + e.getMessage()));
        }
    }

    @PostMapping("/statistics/enable")
    @Operation(
        summary = "Enable Statistics",
        description = "Enable Hibernate statistics collection"
    )
    public ResponseEntity<ApiResponse<String>> enableStatistics() {
        try {
            SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                    .unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            
            if (!statistics.isStatisticsEnabled()) {
                statistics.setStatisticsEnabled(true);
                log.info("Hibernate statistics enabled by admin request");
                return ResponseEntity.ok(ApiResponse.success("Statistics enabled successfully"));
            } else {
                return ResponseEntity.ok(ApiResponse.success("Statistics are already enabled"));
            }
            
        } catch (Exception e) {
            log.error("Failed to enable Hibernate statistics", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to enable statistics: " + e.getMessage()));
        }
    }

    @PostMapping("/statistics/disable")
    @Operation(
        summary = "Disable Statistics",
        description = "Disable Hibernate statistics collection"
    )
    public ResponseEntity<ApiResponse<String>> disableStatistics() {
        try {
            SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                    .unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            
            if (statistics.isStatisticsEnabled()) {
                statistics.setStatisticsEnabled(false);
                log.info("Hibernate statistics disabled by admin request");
                return ResponseEntity.ok(ApiResponse.success("Statistics disabled successfully"));
            } else {
                return ResponseEntity.ok(ApiResponse.success("Statistics are already disabled"));
            }
            
        } catch (Exception e) {
            log.error("Failed to disable Hibernate statistics", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to disable statistics: " + e.getMessage()));
        }
    }

    @PostMapping("/log-summary")
    @Operation(
        summary = "Log Statistics Summary",
        description = "Log current statistics summary to application logs"
    )
    public ResponseEntity<ApiResponse<String>> logStatisticsSummary() {
        try {
            SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                    .unwrap(SessionFactory.class);
            Statistics statistics = sessionFactory.getStatistics();
            
            if (!statistics.isStatisticsEnabled()) {
                return ResponseEntity.ok(ApiResponse.error("Statistics are not enabled"));
            }
            
            // Log comprehensive statistics summary
            log.info("=== Admin-Requested Hibernate Statistics Summary ===");
            log.info("Query Execution Count: {}", statistics.getQueryExecutionCount());
            log.info("Query Execution Max Time: {}ms", statistics.getQueryExecutionMaxTime());
            log.info("Slow Query Count: {}", statistics.getSlowQueryCount());
            log.info("Entity Load Count: {}", statistics.getEntityLoadCount());
            log.info("Second Level Cache Hit Count: {}", statistics.getSecondLevelCacheHitCount());
            log.info("Second Level Cache Miss Count: {}", statistics.getSecondLevelCacheMissCount());
            
            long hits = statistics.getSecondLevelCacheHitCount();
            long misses = statistics.getSecondLevelCacheMissCount();
            if (hits + misses > 0) {
                double hitRatio = (double) hits / (hits + misses) * 100;
                log.info("Cache Hit Ratio: {:.2f}%", hitRatio);
            }
            
            log.info("Session Open Count: {}", statistics.getSessionOpenCount());
            log.info("Transaction Count: {}", statistics.getTransactionCount());
            log.info("====================================================");
            
            if (statementInspector != null) {
                statementInspector.logStatisticsSummary();
            }
            
            return ResponseEntity.ok(ApiResponse.success("Statistics summary logged successfully"));
            
        } catch (Exception e) {
            log.error("Failed to log statistics summary", e);
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Failed to log statistics: " + e.getMessage()));
        }
    }
}
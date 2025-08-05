package com.smmpanel.test.performance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Performance measurement result for database operations
 * Contains detailed metrics about query execution and performance
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPerformanceResult {
    
    private String operationName;
    private long queryCount;
    private long entityLoadCount;
    private long collectionLoadCount;
    private long cacheHitCount;
    private long sessionCount;
    private long executionTimeMs;
    
    /**
     * Calculate queries per millisecond
     */
    public double getQueriesPerMs() {
        return executionTimeMs > 0 ? (double) queryCount / executionTimeMs : 0;
    }
    
    /**
     * Calculate cache hit ratio
     */
    public double getCacheHitRatio() {
        long totalLoads = entityLoadCount + collectionLoadCount;
        return totalLoads > 0 ? (double) cacheHitCount / totalLoads : 0;
    }
    
    /**
     * Check if performance is within acceptable limits
     */
    public boolean isWithinQueryLimit(int maxQueries) {
        return queryCount <= maxQueries;
    }
    
    /**
     * Check if execution time is reasonable (less than 1 second for most operations)
     */
    public boolean isWithinTimeLimit(long maxTimeMs) {
        return executionTimeMs <= maxTimeMs;
    }
    
    /**
     * Generate performance summary
     */
    public String getPerformanceSummary() {
        return String.format(
            "Operation: %s | Queries: %d | Entities: %d | Collections: %d | " +
            "Cache Hits: %d (%.1f%%) | Sessions: %d | Time: %dms | Q/ms: %.3f",
            operationName,
            queryCount,
            entityLoadCount,
            collectionLoadCount,
            cacheHitCount,
            getCacheHitRatio() * 100,
            sessionCount,
            executionTimeMs,
            getQueriesPerMs()
        );
    }
    
    @Override
    public String toString() {
        return getPerformanceSummary();
    }
    
    /**
     * Create a result indicating a performance issue
     */
    public static QueryPerformanceResult failed(String operationName, String reason) {
        return QueryPerformanceResult.builder()
                .operationName(operationName + " (FAILED: " + reason + ")")
                .queryCount(-1)
                .entityLoadCount(-1)
                .collectionLoadCount(-1)
                .cacheHitCount(-1)
                .sessionCount(-1)
                .executionTimeMs(-1)
                .build();
    }
    
    /**
     * Compare performance with another result
     */
    public PerformanceComparison compareWith(QueryPerformanceResult other) {
        return PerformanceComparison.builder()
                .baselineResult(other)
                .currentResult(this)
                .queryCountDelta(this.queryCount - other.queryCount)
                .executionTimeDelta(this.executionTimeMs - other.executionTimeMs)
                .cacheHitRatioDelta(this.getCacheHitRatio() - other.getCacheHitRatio())
                .build();
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceComparison {
        private QueryPerformanceResult baselineResult;
        private QueryPerformanceResult currentResult;
        private long queryCountDelta;
        private long executionTimeDelta;
        private double cacheHitRatioDelta;
        
        public boolean isImprovement() {
            return queryCountDelta <= 0 && executionTimeDelta <= 0;
        }
        
        public boolean isRegression() {
            return queryCountDelta > 0 || executionTimeDelta > currentResult.executionTimeMs * 0.2; // 20% slower
        }
        
        public String getSummary() {
            return String.format(
                "Performance comparison: Queries %+d, Time %+dms (%.1f%%), Cache Hit Ratio %+.1f%%",
                queryCountDelta,
                executionTimeDelta,
                currentResult.executionTimeMs > 0 ? 
                    (double) executionTimeDelta / currentResult.executionTimeMs * 100 : 0,
                cacheHitRatioDelta * 100
            );
        }
    }
}
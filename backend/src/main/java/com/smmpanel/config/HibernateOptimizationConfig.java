package com.smmpanel.config;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataBuilder;
import org.hibernate.boot.spi.MetadataBuilderContributor;
import org.hibernate.cache.jcache.ConfigSettings;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.spi.CachingProvider;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive Hibernate optimization configuration
 * Handles batch processing, query plan caching, second-level caching, and monitoring
 */
@Configuration
@Slf4j
public class HibernateOptimizationConfig {

    @Value("${hibernate.jdbc.batch_size:25}")
    private int batchSize;
    
    @Value("${hibernate.query.plan_cache_max_size:2048}")
    private int queryPlanCacheSize;
    
    @Value("${hibernate.cache.use_second_level_cache:true}")
    private boolean useSecondLevelCache;
    
    @Value("${hibernate.statistics.enabled:true}")
    private boolean statisticsEnabled;

    /**
     * Hibernate properties customizer for advanced optimizations
     */
    @Bean
    public HibernatePropertiesCustomizer hibernatePropertiesCustomizer() {
        return (properties) -> {
            // ===============================
            // JDBC BATCH OPTIMIZATION
            // ===============================
            properties.put("hibernate.jdbc.batch_size", batchSize);
            properties.put("hibernate.jdbc.batch_versioned_data", true);
            properties.put("hibernate.jdbc.fetch_size", Math.max(batchSize * 2, 50));
            properties.put("hibernate.order_inserts", true);
            properties.put("hibernate.order_updates", true);
            
            // ===============================
            // QUERY PLAN CACHING
            // ===============================
            properties.put("hibernate.query.plan_cache_max_size", queryPlanCacheSize);
            properties.put("hibernate.query.plan_parameter_metadata_max_size", queryPlanCacheSize / 16);
            properties.put("hibernate.query.in_clause_parameter_padding", true);
            
            // ===============================
            // SECOND LEVEL CACHE CONFIGURATION
            // ===============================
            if (useSecondLevelCache) {
                properties.put("hibernate.cache.use_second_level_cache", true);
                properties.put("hibernate.cache.use_query_cache", true);
                properties.put("hibernate.cache.region.factory_class", 
                    "org.hibernate.cache.jcache.JCacheRegionFactory");
                properties.put(ConfigSettings.PROVIDER, 
                    "org.ehcache.jsr107.EhcacheCachingProvider");
                properties.put("hibernate.cache.default_cache_concurrency_strategy", "read_write");
            }
            
            // ===============================
            // STATISTICS AND MONITORING
            // ===============================
            properties.put("hibernate.generate_statistics", statisticsEnabled);
            properties.put("hibernate.session.events.log.LOG_QUERIES_SLOWER_THAN_MS", 100);
            
            // ===============================
            // PERFORMANCE OPTIMIZATIONS
            // ===============================
            properties.put("hibernate.default_batch_fetch_size", 16);
            properties.put("hibernate.max_fetch_depth", 3);
            properties.put("hibernate.enable_lazy_load_no_trans", false);
            properties.put("hibernate.connection.provider_disables_autocommit", true);
            properties.put("hibernate.temp.use_jdbc_metadata_defaults", false);
            
            log.info("Hibernate optimization properties configured:");
            log.info("  - Batch size: {}", batchSize);
            log.info("  - Query plan cache size: {}", queryPlanCacheSize);
            log.info("  - Second level cache: {}", useSecondLevelCache);
            log.info("  - Statistics enabled: {}", statisticsEnabled);
        };
    }

    /**
     * Configure JCache (EhCache) for second-level cache
     */
    @Bean(name = "hibernateCacheManager")
    public CacheManager hibernateCacheManager() {
        if (!useSecondLevelCache) {
            return null;
        }
        
        try {
            CachingProvider cachingProvider = Caching.getCachingProvider("org.ehcache.jsr107.EhcacheCachingProvider");
            CacheManager cacheManager = cachingProvider.getCacheManager();
            
            // Configure default cache for entities
            MutableConfiguration<Object, Object> entityCacheConfig = new MutableConfiguration<>()
                    .setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(Duration.THIRTY_MINUTES))
                    .setStoreByValue(false)
                    .setStatisticsEnabled(true);
            
            // Configure cache for query results
            MutableConfiguration<Object, Object> queryCacheConfig = new MutableConfiguration<>()
                    .setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(Duration.TEN_MINUTES))
                    .setStoreByValue(false)
                    .setStatisticsEnabled(true);
            
            // Create caches for different entity types
            cacheManager.createCache("com.smmpanel.entity.Service", entityCacheConfig);
            cacheManager.createCache("com.smmpanel.entity.User", entityCacheConfig);
            cacheManager.createCache("com.smmpanel.entity.ConversionCoefficient", entityCacheConfig);
            cacheManager.createCache("org.hibernate.cache.spi.QueryResultsRegion", queryCacheConfig);
            cacheManager.createCache("default-query-results-region", queryCacheConfig);
            
            log.info("Hibernate second-level cache configured with EhCache");
            return cacheManager;
            
        } catch (Exception e) {
            log.warn("Failed to configure Hibernate cache manager, falling back to no cache", e);
            return null;
        }
    }

    /**
     * Custom metadata builder contributor for advanced Hibernate configuration
     */
    @Component
    public static class CustomMetadataBuilderContributor implements MetadataBuilderContributor {
        
        @Override
        public void contribute(MetadataBuilder metadataBuilder) {
            // Additional metadata customizations can be added here
            log.debug("Custom Hibernate metadata builder contributor applied");
        }
    }

    /**
     * Hibernate statistics health indicator
     */
    @Component
    @Profile("!test")
    public static class HibernateStatisticsHealthIndicator implements HealthIndicator {
        
        @PersistenceContext
        private EntityManager entityManager;
        
        @Value("${hibernate.statistics.health.slow-query-threshold:5}")
        private int slowQueryThreshold;
        
        @Override
        public Health health() {
            try {
                SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                        .unwrap(SessionFactory.class);
                Statistics statistics = sessionFactory.getStatistics();
                
                if (!statistics.isStatisticsEnabled()) {
                    return Health.up()
                            .withDetail("statistics", "disabled")
                            .build();
                }
                
                long queryCount = statistics.getQueryExecutionCount();
                long slowQueryCount = statistics.getSlowQueryCount();
                double cacheHitRatio = statistics.getSecondLevelCacheHitCount() > 0 ? 
                    (double) statistics.getSecondLevelCacheHitCount() / 
                    (statistics.getSecondLevelCacheHitCount() + statistics.getSecondLevelCacheMissCount()) : 0;
                
                Health.Builder healthBuilder = Health.up()
                        .withDetail("queryExecutionCount", queryCount)
                        .withDetail("slowQueryCount", slowQueryCount)
                        .withDetail("entityLoadCount", statistics.getEntityLoadCount())
                        .withDetail("collectionLoadCount", statistics.getCollectionLoadCount())
                        .withDetail("secondLevelCacheHitCount", statistics.getSecondLevelCacheHitCount())
                        .withDetail("secondLevelCacheMissCount", statistics.getSecondLevelCacheMissCount())
                        .withDetail("cacheHitRatio", String.format("%.2f%%", cacheHitRatio * 100))
                        .withDetail("sessionOpenCount", statistics.getSessionOpenCount())
                        .withDetail("sessionCloseCount", statistics.getSessionCloseCount())
                        .withDetail("transactionCount", statistics.getTransactionCount());
                
                // Check if there are too many slow queries
                if (slowQueryCount > slowQueryThreshold) {
                    healthBuilder.down()
                            .withDetail("issue", "High number of slow queries detected");
                }
                
                return healthBuilder.build();
                
            } catch (Exception e) {
                return Health.down()
                        .withDetail("error", e.getMessage())
                        .build();
            }
        }
    }

    /**
     * Hibernate statistics monitoring component
     */
    @Component
    @Profile("!test")
    public static class HibernateStatisticsMonitor {
        
        @PersistenceContext
        private EntityManager entityManager;
        
        @Autowired(required = false)
        private HibernateStatementInspector statementInspector;
        
        @Value("${hibernate.statistics.log-interval-minutes:10}")
        private int logIntervalMinutes;
        
        @Scheduled(fixedRateString = "#{${hibernate.statistics.log-interval-minutes:10} * 60 * 1000}")
        public void logStatistics() {
            try {
                SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                        .unwrap(SessionFactory.class);
                Statistics statistics = sessionFactory.getStatistics();
                
                if (!statistics.isStatisticsEnabled()) {
                    return;
                }
                
                log.info("=== Hibernate Statistics Report ===");
                log.info("Query Execution Count: {}", statistics.getQueryExecutionCount());
                log.info("Query Execution Max Time: {}ms", statistics.getQueryExecutionMaxTime());
                log.info("Query Execution Max Time Query: {}", statistics.getQueryExecutionMaxTimeQueryString());
                log.info("Slow Query Count: {}", statistics.getSlowQueryCount());
                
                log.info("Entity Statistics:");
                log.info("  - Load Count: {}", statistics.getEntityLoadCount());
                log.info("  - Fetch Count: {}", statistics.getEntityFetchCount());
                log.info("  - Insert Count: {}", statistics.getEntityInsertCount());
                log.info("  - Update Count: {}", statistics.getEntityUpdateCount());
                log.info("  - Delete Count: {}", statistics.getEntityDeleteCount());
                
                log.info("Collection Statistics:");
                log.info("  - Load Count: {}", statistics.getCollectionLoadCount());
                log.info("  - Fetch Count: {}", statistics.getCollectionFetchCount());
                log.info("  - Update Count: {}", statistics.getCollectionUpdateCount());
                log.info("  - Remove Count: {}", statistics.getCollectionRemoveCount());
                log.info("  - Recreate Count: {}", statistics.getCollectionRecreateCount());
                
                if (statistics.getSecondLevelCacheHitCount() > 0 || statistics.getSecondLevelCacheMissCount() > 0) {
                    long hits = statistics.getSecondLevelCacheHitCount();
                    long misses = statistics.getSecondLevelCacheMissCount();
                    double hitRatio = (double) hits / (hits + misses) * 100;
                    
                    log.info("Second Level Cache Statistics:");
                    log.info("  - Hit Count: {}", hits);
                    log.info("  - Miss Count: {}", misses);
                    log.info("  - Hit Ratio: {:.2f}%", hitRatio);
                    log.info("  - Put Count: {}", statistics.getSecondLevelCachePutCount());
                }
                
                log.info("Session Statistics:");
                log.info("  - Open Count: {}", statistics.getSessionOpenCount());
                log.info("  - Close Count: {}", statistics.getSessionCloseCount());
                log.info("  - Transaction Count: {}", statistics.getTransactionCount());
                log.info("  - Successful Transaction Count: {}", statistics.getSuccessfulTransactionCount());
                
                // Log statement inspector statistics if available
                if (statementInspector != null) {
                    statementInspector.logStatisticsSummary();
                }
                
                log.info("=====================================");
                
            } catch (Exception e) {
                log.error("Failed to log Hibernate statistics", e);
            }
        }
        
        @Scheduled(fixedRateString = "#{${hibernate.statistics.reset-interval-hours:24} * 60 * 60 * 1000}")
        public void resetStatistics() {
            try {
                SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                        .unwrap(SessionFactory.class);
                Statistics statistics = sessionFactory.getStatistics();
                
                if (statistics.isStatisticsEnabled()) {
                    statistics.clear();
                    if (statementInspector != null) {
                        statementInspector.resetStatistics();
                    }
                    log.info("Hibernate statistics reset");
                }
                
            } catch (Exception e) {
                log.error("Failed to reset Hibernate statistics", e);
            }
        }
    }

    /**
     * Hibernate cache statistics endpoint
     */
    @Component
    @Profile("!test")
    public static class HibernateCacheStatistics {
        
        @PersistenceContext
        private EntityManager entityManager;
        
        public Map<String, Object> getCacheStatistics() {
            try {
                SessionFactory sessionFactory = entityManager.getEntityManagerFactory()
                        .unwrap(SessionFactory.class);
                Statistics statistics = sessionFactory.getStatistics();
                
                return Map.of(
                    "enabled", statistics.isStatisticsEnabled(),
                    "secondLevelCacheEnabled", statistics.getSecondLevelCacheHitCount() >= 0,
                    "cacheHitCount", statistics.getSecondLevelCacheHitCount(),
                    "cacheMissCount", statistics.getSecondLevelCacheMissCount(),
                    "cachePutCount", statistics.getSecondLevelCachePutCount(),
                    "cacheHitRatio", calculateCacheHitRatio(statistics),
                    "entityNames", statistics.getEntityNames(),
                    "collectionRoleNames", statistics.getCollectionRoleNames(),
                    "queryCacheHitCount", statistics.getQueryCacheHitCount(),
                    "queryCacheMissCount", statistics.getQueryCacheMissCount(),
                    "queryCachePutCount", statistics.getQueryCachePutCount()
                );
                
            } catch (Exception e) {
                log.error("Failed to get cache statistics", e);
                return Map.of("error", e.getMessage());
            }
        }
        
        private double calculateCacheHitRatio(Statistics statistics) {
            long hits = statistics.getSecondLevelCacheHitCount();
            long misses = statistics.getSecondLevelCacheMissCount();
            return hits + misses > 0 ? (double) hits / (hits + misses) * 100 : 0;
        }
    }
}
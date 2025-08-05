# Hibernate Configuration Guide

This document provides comprehensive information about the Hibernate configuration optimizations implemented in the SMM Panel application.

## Overview

The Hibernate configuration has been optimized for performance, monitoring, and scalability with the following key features:

- **Statistics Collection** - Comprehensive performance monitoring
- **JDBC Batch Processing** - Optimized batch operations
- **Query Plan Caching** - Improved query compilation performance
- **Second-Level Caching** - Entity and query result caching
- **Statement Inspection** - Custom query analysis and monitoring

## Configuration Files

### 1. Main Configuration (`application.yml`)

```yaml
spring:
  jpa:
    properties:
      hibernate:
        # Statistics enabled for monitoring
        generate_statistics: true
        
        # JDBC batch optimization
        jdbc:
          batch_size: 25
          batch_versioned_data: true
          fetch_size: 50
        
        # Query plan caching
        query:
          plan_cache_max_size: 2048
          plan_parameter_metadata_max_size: 128
        
        # Second-level cache
        cache:
          use_second_level_cache: true
          use_query_cache: true
          region:
            factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
```

### 2. Development Configuration (`application-dev.yml`)

Enhanced configuration for development with detailed logging:

```yaml
spring:
  jpa:
    show-sql: true
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true
        highlight_sql: true
        
        # Enhanced batch sizes for development
        jdbc:
          batch_size: 50
          fetch_size: 100
        
        # Larger caches for development
        query:
          plan_cache_max_size: 4096
          plan_parameter_metadata_max_size: 256
```

### 3. EhCache Configuration (`ehcache.xml`)

Comprehensive cache configuration with optimized settings for different entity types and usage patterns.

## Key Optimizations

### 1. JDBC Batch Processing

**Configuration:**
- `hibernate.jdbc.batch_size: 25` - Process 25 operations per batch
- `hibernate.jdbc.batch_versioned_data: true` - Enable batch updates for versioned entities
- `hibernate.jdbc.fetch_size: 50` - Fetch 50 rows at a time
- `hibernate.order_inserts: true` - Group INSERT statements
- `hibernate.order_updates: true` - Group UPDATE statements

**Benefits:**
- Reduces database round trips
- Improves performance for bulk operations
- Better resource utilization

**Monitoring:**
```java
// Check batch processing effectiveness
Statistics stats = sessionFactory.getStatistics();
long batchSize = stats.getEntityInsertCount() / stats.getTransactionCount();
```

### 2. Query Plan Caching

**Configuration:**
- `hibernate.query.plan_cache_max_size: 2048` - Cache up to 2048 query plans
- `hibernate.query.plan_parameter_metadata_max_size: 128` - Cache parameter metadata
- `hibernate.query.in_clause_parameter_padding: true` - Optimize IN clause queries

**Benefits:**
- Faster query compilation
- Reduced CPU usage for repeated queries
- Better application startup performance

**Monitoring:**
```java
// Query plan cache effectiveness is monitored through statistics
// Higher query execution counts with stable compilation times indicate effective caching
```

### 3. Second-Level Caching

**Enabled Entities:**
- `User` - 20 minute TTL, READ_WRITE strategy
- `Service` - 4 hour TTL, READ_WRITE strategy (static data)
- `ConversionCoefficient` - 6 hour TTL, READ_WRITE strategy
- `Order` - 10 minute TTL, READ_WRITE strategy
- Collections with appropriate batch sizes and TTL

**Configuration per Entity Type:**

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Service {
    // Entity frequently read, rarely updated
}

@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class User {
    @OneToMany(mappedBy = "user")
    @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    private List<Order> orders;
}
```

**Cache Regions:**
- **Entity Cache** - Individual entity instances
- **Collection Cache** - Entity collections (OneToMany, ManyToMany)
- **Query Cache** - Query result sets
- **Update Timestamps** - Cache invalidation tracking

### 4. Statistics and Monitoring

**Enabled Statistics:**
- Query execution counts and timing
- Entity CRUD operation counts
- Cache hit/miss ratios
- Session and transaction metrics
- Connection usage statistics

**Custom Statement Inspector:**
```java
@Component
public class HibernateStatementInspector implements StatementInspector {
    // Tracks slow queries, N+1 patterns, and performance issues
    // Provides detailed query analysis and recommendations
}
```

## Monitoring and Administration

### 1. Admin Endpoints

Access comprehensive monitoring through admin endpoints:

```bash
# Get complete Hibernate statistics
GET /api/v1/admin/hibernate/statistics

# Get cache-specific statistics
GET /api/v1/admin/hibernate/cache-statistics

# Get query inspector analysis
GET /api/v1/admin/hibernate/query-inspector

# Reset statistics counters
POST /api/v1/admin/hibernate/statistics/reset

# Enable/disable statistics collection
POST /api/v1/admin/hibernate/statistics/enable
POST /api/v1/admin/hibernate/statistics/disable
```

### 2. Health Indicators

Automatic health monitoring through Spring Boot Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,hibernate
  endpoint:
    hibernate:
      enabled: true
```

### 3. Scheduled Monitoring

Automatic statistics logging every 10 minutes:

```java
@Scheduled(fixedRateString = "#{${hibernate.statistics.log-interval-minutes:10} * 60 * 1000}")
public void logStatistics() {
    // Comprehensive statistics logging
}
```

## Performance Testing

### 1. Cache Effectiveness Tests

Run comprehensive cache tests:

```bash
# Run second-level cache tests
./gradlew test --tests "HibernateSecondLevelCacheTest"

# Run performance tests with cache analysis
./gradlew performanceTest -Phibernate.cache.test=true
```

### 2. Batch Processing Tests

Validate batch processing effectiveness:

```java
@Test
public void testBatchProcessing() {
    // Measure query count vs entity count ratio
    // Should be significantly less than 1:1 for bulk operations
}
```

### 3. Query Plan Cache Tests

Monitor query compilation performance:

```java
@Test
public void testQueryPlanCache() {
    // Execute same query multiple times
    // Compilation time should decrease after first execution
}
```

## Configuration by Environment

### Development Environment

```yaml
hibernate:
  statistics:
    enabled: true
    log-interval-minutes: 5
  cache:
    enabled: true
    ttl-multiplier: 0.5  # Shorter TTL for development
  batch:
    size: 50  # Larger batches for development
  logging:
    sql: true
    parameters: true
    slow-query-threshold: 50ms
```

### Production Environment

```yaml
hibernate:
  statistics:
    enabled: true
    log-interval-minutes: 60
  cache:
    enabled: true
    ttl-multiplier: 2.0  # Longer TTL for production
  batch:
    size: 25  # Conservative batch size
  logging:
    sql: false
    parameters: false
    slow-query-threshold: 200ms
```

### Test Environment

```yaml
hibernate:
  statistics:
    enabled: true
    reset-on-startup: true
  cache:
    enabled: false  # Disable cache for deterministic tests
  batch:
    size: 10  # Smaller batches for test predictability
  logging:
    sql: true
    parameters: true
```

## Common Performance Issues

### 1. N+1 Query Problems

**Detection:**
- Statement inspector logs warnings for potential N+1 patterns
- High query count relative to entity count

**Solution:**
```java
// Use JOIN FETCH or batch fetching
@Query("SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service")
List<Order> findOrdersWithDetails();

// Or use @BatchSize on collections
@OneToMany(fetch = FetchType.LAZY)
@BatchSize(size = 25)
private List<Order> orders;
```

### 2. Cache Miss Issues

**Detection:**
- Low cache hit ratios in statistics
- High database query counts for cached entities

**Solution:**
```java
// Ensure entities are properly annotated for caching
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class MyEntity {
    // ...
}

// Use query cache for frequently executed queries
@QueryHints({
    @QueryHint(name = "org.hibernate.cacheable", value = "true"),
    @QueryHint(name = "org.hibernate.cacheRegion", value = "my-query-cache")
})
List<Entity> findCacheableData();
```

### 3. Batch Processing Issues

**Detection:**
- High transaction count relative to entity modification count
- Statement inspector shows individual INSERT/UPDATE patterns

**Solution:**
```java
// Use batch operations
@Modifying
@Query("UPDATE Entity e SET e.status = :status WHERE e.id IN :ids")
void updateStatusBatch(@Param("status") Status status, @Param("ids") List<Long> ids);

// Configure proper batch size
hibernate.jdbc.batch_size=25
hibernate.order_inserts=true
hibernate.order_updates=true
```

## Troubleshooting Guide

### 1. Cache Not Working

**Symptoms:**
- Zero cache hits in statistics
- High database query counts

**Diagnosis:**
```bash
# Check cache statistics
curl -X GET /api/v1/admin/hibernate/cache-statistics

# Check entity annotations
grep -r "@Cache" src/main/java/com/smmpanel/entity/
```

**Solutions:**
1. Verify `@Cache` annotations on entities
2. Check EhCache configuration
3. Ensure cache provider is properly configured
4. Verify cache regions are created

### 2. Poor Batch Performance

**Symptoms:**
- High query count for bulk operations
- Individual INSERT/UPDATE statements in logs

**Diagnosis:**
```java
// Check batch effectiveness
long batchEffectiveness = entityUpdateCount / queryExecutionCount;
// Should be > 1 for good batch performance
```

**Solutions:**
1. Increase batch size configuration
2. Use batch operations where possible
3. Ensure batch_versioned_data is enabled
4. Check for @GeneratedValue strategy issues

### 3. Memory Issues

**Symptoms:**
- OutOfMemoryError with cache usage
- High heap usage

**Diagnosis:**
```bash
# Monitor cache sizes
curl -X GET /api/v1/admin/hibernate/statistics | jq '.cacheStatistics'
```

**Solutions:**
1. Reduce cache TTL values
2. Implement cache size limits
3. Use off-heap storage for large caches
4. Monitor and tune cache regions

## Best Practices

### 1. Entity Design

```java
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)  // For frequently read entities
public class StaticDataEntity {
    // Use appropriate cache strategy based on access patterns
}

@Entity
@Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)  // For rarely updated entities
public class ConfigurationEntity {
    // Less strict consistency for better performance
}
```

### 2. Query Optimization

```java
// Use query cache for expensive queries
@QueryHints({
    @QueryHint(name = "org.hibernate.cacheable", value = "true"),
    @QueryHint(name = "org.hibernate.cacheRegion", value = "statistics-queries")
})
@Query("SELECT COUNT(o) FROM Order o WHERE o.status = :status")
Long countByStatus(@Param("status") OrderStatus status);
```

### 3. Monitoring Integration

```java
// Regular monitoring in production
@Component
public class HibernateMonitor {
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        // Log initial cache configuration
        logCacheConfiguration();
    }
}
```

This comprehensive Hibernate configuration provides optimal performance while maintaining monitoring capabilities and scalability for the SMM Panel application.
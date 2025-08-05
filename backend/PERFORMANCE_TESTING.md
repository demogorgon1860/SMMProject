# Query Performance Testing Guide

This guide explains how to run and interpret query performance tests for the SMM Panel application.

## Overview

The query performance testing suite measures actual SQL query execution and ensures that database operations remain efficient and scalable. The tests count real SQL queries using Hibernate statistics and assert that operations stay within defined performance limits.

## Test Categories

### 1. Common User Operations Tests
**File:** `CommonUserOperationsPerformanceTest.java`

Tests everyday user operations:
- User registration and profile loading
- Service listing
- Order creation
- Order history browsing
- Single order details fetch
- Balance checks
- Dashboard data loading

**Performance Thresholds:**
- Order Creation: ≤ 3 queries, < 500ms
- Order Listing: ≤ 2 queries, < 300ms
- Single Order Fetch: ≤ 1 query, < 100ms
- Balance Check: ≤ 1 query, < 50ms

### 2. Repository Query Performance Tests
**File:** `RepositoryQueryPerformanceTest.java`

Tests optimized repository methods:
- JOIN FETCH query effectiveness
- Batch fetching performance
- Complex search scenarios
- Error recovery query optimization
- Concurrent access simulation

### 3. Data Volume Performance Tests
**File:** `DataVolumePerformanceTest.java`

Tests scalability with different data volumes:
- Variable user counts (10, 50, 100, 500)
- Variable service counts (5, 25, 50, 100)
- Variable order volumes (10, 50, 100, 200 per user)
- Performance comparison: small vs large datasets
- Memory pressure testing

### 4. Integration Test Suite
**File:** `QueryPerformanceTestSuite.java`

Runs all performance tests and generates comprehensive reports.

## Running Performance Tests

### Quick Performance Test
```bash
# Run all performance tests
./gradlew performanceTest

# Run with detailed output
./gradlew performanceTest --info

# Run specific test class
./gradlew performanceTest --tests "CommonUserOperationsPerformanceTest"
```

### Performance Testing with Profiling
```bash
# Run with JVM profiling enabled
./gradlew performanceTestWithProfiling

# Run continuous performance testing (5 iterations)
./gradlew continuousPerformanceTest

# Run with custom iteration count
./gradlew continuousPerformanceTest -Piterations=10
```

### CI/CD Integration
```bash
# Run performance tests as part of build pipeline
./gradlew check  # Includes performance tests

# Run only unit tests (excludes performance tests)
./gradlew test
```

## Test Configuration

### Environment Configuration
Performance tests use the `test` profile with:
- **Database:** H2 in-memory with PostgreSQL compatibility mode
- **Hibernate Statistics:** Enabled for query counting
- **SQL Logging:** Enabled with parameter binding
- **JVM Options:** Optimized for performance testing

### Key Configuration Files
- `application-test.yml` - Test environment configuration
- `QueryPerformanceTestBase.java` - Base test utilities
- `performance-testing.gradle` - Gradle task configuration

### Performance Thresholds
Configurable in `application-test.yml`:
```yaml
app:
  performance:
    max-queries:
      order-creation: 3
      order-listing: 2
      single-order-fetch: 1
      transaction-history: 2
      balance-check: 1
      service-listing: 1
      user-profile: 1
      bulk-operations: 5
```

## Understanding Test Results

### Query Count Metrics
- **Query Count:** Total SQL queries executed
- **Entity Load Count:** Number of entities loaded from database
- **Collection Load Count:** Number of collections loaded
- **Cache Hit Count:** Number of cache hits
- **Execution Time:** Total operation time in milliseconds

### Success Criteria
Tests pass when:
1. **Query count** ≤ defined threshold
2. **Execution time** ≤ defined time limit
3. **No N+1 query patterns** detected
4. **Relationships properly fetched** without lazy loading exceptions

### Example Test Output
```
✓ Query count assertion passed for Order Creation: 2 <= 3
✓ Optimized single order fetch: Queries=1, Entities=3, Collections=0, Time=45ms
❌ Query count exceeded limit for User Listing: expected <= 2, actual = 5
```

## Performance Reports

### Automatic Report Generation
After running performance tests, reports are generated:

**Markdown Report:** `target/query-performance-report.md`
- Detailed test results
- Performance analysis
- Optimization recommendations

**Console Summary:**
```
📊 QUERY PERFORMANCE TEST SUMMARY
=====================================
Total Tests: 15
Successful: 14 ✅
Failed: 1 ❌
🎉 ALL PERFORMANCE TESTS PASSED!
```

### Report Contents
1. **Executive Summary** - Pass/fail overview
2. **Detailed Results** - Individual test performance
3. **Performance Guidelines** - Expected thresholds
4. **Optimization Techniques** - Applied optimizations
5. **Next Steps** - Recommendations for improvements

## Troubleshooting Performance Issues

### High Query Counts
**Symptoms:** Tests fail with query count > threshold

**Common Causes:**
- Missing `JOIN FETCH` clauses in repository queries
- Lazy loading in loops (N+1 problem)
- Missing `@BatchSize` annotations

**Solutions:**
1. Add `JOIN FETCH` to repository queries
2. Use optimized repository methods with relationships pre-loaded
3. Implement batch fetching with `@BatchSize`

### Slow Execution Times
**Symptoms:** Tests pass query count but fail time assertions

**Common Causes:**
- Large result sets without pagination
- Missing database indexes
- Inefficient query structure

**Solutions:**
1. Implement proper pagination
2. Add database indexes for common queries
3. Optimize query structure and JOINs

### Memory Issues
**Symptoms:** Tests fail with OutOfMemoryError

**Common Causes:**
- Loading large collections without limits
- Entity cache growing too large
- Missing pagination in bulk operations

**Solutions:**
1. Use pagination for all listing operations
2. Configure entity cache limits
3. Implement streaming for large data processing

## Best Practices

### Writing Performance Tests
1. **Extend QueryPerformanceTestBase** for utilities
2. **Clear session and stats** before measurements
3. **Test realistic scenarios** with appropriate data volumes
4. **Assert both query count and execution time**
5. **Test relationship access** to verify JOIN FETCH

### Test Data Management
1. **Use consistent test data** with fixed random seed
2. **Create appropriate data volumes** for realistic testing
3. **Test edge cases** (empty results, large datasets)
4. **Clean up between tests** to avoid interference

### Performance Assertions
```java
// Measure performance
QueryPerformanceResult result = measureQueryPerformance("Operation Name", () -> {
    // Your operation here
});

// Assert performance limits
assertQueryCountWithinLimit(result, maxQueries);
assert result.getExecutionTimeMs() < maxTimeMs;
```

## Integration with Monitoring

### Production Monitoring
Use `QueryCountMonitoringAspect` to monitor query performance in production:

```yaml
app:
  monitoring:
    query-count:
      enabled: true
      warn-threshold: 5
      error-threshold: 10
```

### Alerts and Dashboards
Set up monitoring for:
- Query execution counts per endpoint
- Average response times
- Database connection pool usage
- Cache hit/miss ratios

### Continuous Performance Testing
Include performance tests in CI/CD pipeline:
```yaml
# GitHub Actions / Jenkins example
- name: Run Performance Tests
  run: ./gradlew performanceTest
  
- name: Generate Performance Report
  run: ./gradlew generatePerformanceReport
  
- name: Upload Performance Report
  uses: actions/upload-artifact@v2
  with:
    name: performance-report
    path: target/query-performance-report.md
```

## Common Query Optimizations Applied

### 1. N+1 Query Prevention
```java
// Before (N+1 problem)
@Query("SELECT o FROM Order o WHERE o.user.id = :userId")
List<Order> findByUserId(Long userId);

// After (optimized with JOIN FETCH)
@Query("SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.service WHERE o.user.id = :userId")
List<Order> findByUserIdWithDetails(Long userId);
```

### 2. Batch Fetching
```java
@Entity
public class User {
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    @BatchSize(size = 25)  // Load in batches of 25
    private List<Order> orders;
}
```

### 3. Entity Graphs
```java
@EntityGraph(attributePaths = {"user", "service"})
Page<Order> findByUser(User user, Pageable pageable);
```

### 4. Pagination
```java
// Always use pagination for lists
Pageable pageable = PageRequest.of(0, 20);
Page<Order> orders = orderRepository.findByUser(user, pageable);
```

This comprehensive performance testing framework ensures that database queries remain efficient as the application scales and evolves.
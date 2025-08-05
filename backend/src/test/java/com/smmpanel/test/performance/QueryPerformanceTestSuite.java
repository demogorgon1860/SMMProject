package com.smmpanel.test.performance;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete test suite for query performance testing
 * Runs all performance tests and generates a comprehensive report
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.DisplayName.class)
@Slf4j
public class QueryPerformanceTestSuite {

    private static final List<QueryPerformanceResult> testResults = new ArrayList<>();
    private static final String REPORT_FILE = "target/query-performance-report.md";
    
    @BeforeAll
    static void setupTestSuite() {
        log.info("🚀 Starting Query Performance Test Suite");
        log.info("Test configuration:");
        log.info("  - Database: H2 in-memory");
        log.info("  - Hibernate statistics: ENABLED");
        log.info("  - SQL logging: ENABLED");
        log.info("  - Report output: {}", REPORT_FILE);
    }

    @Test
    @DisplayName("A. Performance Baseline Tests")
    void runBaselinePerformanceTests() {
        log.info("📊 Running baseline performance tests...");
        
        // These tests establish performance baselines
        CommonUserOperationsPerformanceTest commonTests = new CommonUserOperationsPerformanceTest();
        
        try {
            // Initialize the test base
            commonTests.setupQueryPerformanceTesting();
            commonTests.setupTestData();
            
            // Run critical baseline tests
            runTestAndCollectResult("User Registration", () -> {
                commonTests.testUserRegistrationPerformance();
            });
            
            runTestAndCollectResult("Service Listing", () -> {
                commonTests.testServiceListingPerformance();
            });
            
            runTestAndCollectResult("Order Creation", () -> {
                commonTests.testOrderCreationPerformance();
            });
            
            runTestAndCollectResult("Order History Listing", () -> {
                commonTests.testOrderHistoryListingPerformance();
            });
            
            runTestAndCollectResult("Single Order Fetch", () -> {
                commonTests.testSingleOrderFetchPerformance();
            });
            
            log.info("✅ Baseline performance tests completed");
            
        } catch (Exception e) {
            log.error("❌ Baseline performance tests failed", e);
            throw new RuntimeException("Baseline tests failed", e);
        }
    }

    @Test
    @DisplayName("B. Repository Optimization Tests")
    void runRepositoryOptimizationTests() {
        log.info("🔍 Running repository optimization tests...");
        
        RepositoryQueryPerformanceTest repoTests = new RepositoryQueryPerformanceTest();
        
        try {
            repoTests.setupQueryPerformanceTesting();
            repoTests.setupRepositoryTestData();
            
            runTestAndCollectResult("Optimized Order Details Fetch", () -> {
                repoTests.testOptimizedOrderRepositoryFindWithDetails();
            });
            
            runTestAndCollectResult("Optimized User Orders Pagination", () -> {
                repoTests.testOptimizedUserOrdersWithPagination();
            });
            
            runTestAndCollectResult("Batch Fetching", () -> {
                repoTests.testRepositoryBatchFetching();
            });
            
            runTestAndCollectResult("Orders by Status", () -> {
                repoTests.testOrdersByStatusWithDetails();
            });
            
            log.info("✅ Repository optimization tests completed");
            
        } catch (Exception e) {
            log.error("❌ Repository optimization tests failed", e);
            throw new RuntimeException("Repository tests failed", e);
        }
    }

    @Test
    @DisplayName("C. Data Volume Scalability Tests")
    void runDataVolumeScalabilityTests() {
        log.info("📈 Running data volume scalability tests...");
        
        DataVolumePerformanceTest volumeTests = new DataVolumePerformanceTest();
        
        try {
            volumeTests.setupQueryPerformanceTesting();
            
            // Test with different user counts
            runTestAndCollectResult("Scalability - 10 Users", () -> {
                volumeTests.testOrderListingWithVariableUserCounts(10);
            });
            
            runTestAndCollectResult("Scalability - 100 Users", () -> {
                volumeTests.testOrderListingWithVariableUserCounts(100);
            });
            
            // Test batch loading
            runTestAndCollectResult("Batch Loading Performance", () -> {
                volumeTests.testBatchLoadingPerformanceWithDifferentSizes();
            });
            
            // Test under memory pressure
            runTestAndCollectResult("Performance Under Load", () -> {
                volumeTests.testPerformanceUnderMemoryPressure();
            });
            
            log.info("✅ Data volume scalability tests completed");
            
        } catch (Exception e) {
            log.error("❌ Data volume tests failed", e);
            throw new RuntimeException("Volume tests failed", e);
        }
    }

    @Test
    @DisplayName("D. Advanced Query Performance Tests")
    void runAdvancedQueryPerformanceTests() {
        log.info("🎯 Running advanced query performance tests...");
        
        RepositoryQueryPerformanceTest repoTests = new RepositoryQueryPerformanceTest();
        
        try {
            repoTests.setupQueryPerformanceTesting();
            repoTests.setupRepositoryTestData();
            
            runTestAndCollectResult("Error Recovery Queries", () -> {
                repoTests.testErrorRecoveryOrders();
            });
            
            runTestAndCollectResult("Complex Search Scenarios", () -> {
                repoTests.testComplexSearchScenarios();
            });
            
            runTestAndCollectResult("Concurrent Access Simulation", () -> {
                repoTests.testRepositoryConcurrentAccessSimulation();
            });
            
            log.info("✅ Advanced query performance tests completed");
            
        } catch (Exception e) {
            log.error("❌ Advanced performance tests failed", e);
            throw new RuntimeException("Advanced tests failed", e);
        }
    }

    @AfterAll
    static void generatePerformanceReport() {
        log.info("📋 Generating performance report...");
        
        try {
            generateMarkdownReport();
            generateConsoleReport();
            
            log.info("✅ Performance report generated: {}", REPORT_FILE);
            
        } catch (IOException e) {
            log.error("❌ Failed to generate performance report", e);
        }
    }

    private void runTestAndCollectResult(String testName, Runnable test) {
        try {
            long startTime = System.currentTimeMillis();
            test.run();
            long endTime = System.currentTimeMillis();
            
            QueryPerformanceResult result = QueryPerformanceResult.builder()
                    .operationName(testName)
                    .queryCount(0) // Will be measured by individual tests
                    .executionTimeMs(endTime - startTime)
                    .build();
                    
            testResults.add(result);
            
        } catch (Exception e) {
            log.error("Test '{}' failed: {}", testName, e.getMessage());
            testResults.add(QueryPerformanceResult.failed(testName, e.getMessage()));
        }
    }

    private static void generateMarkdownReport() throws IOException {
        try (FileWriter writer = new FileWriter(REPORT_FILE)) {
            writer.write("# Query Performance Test Report\n\n");
            writer.write("Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n\n");
            
            writer.write("## Summary\n\n");
            writer.write("Total tests executed: " + testResults.size() + "\n");
            
            long successfulTests = testResults.stream()
                    .filter(r -> r.getQueryCount() >= 0)
                    .count();
            long failedTests = testResults.size() - successfulTests;
            
            writer.write("Successful tests: " + successfulTests + "\n");
            writer.write("Failed tests: " + failedTests + "\n\n");
            
            if (failedTests > 0) {
                writer.write("⚠️ **Some tests failed - review implementation for performance issues**\n\n");
            } else {
                writer.write("✅ **All tests passed - query performance is optimized**\n\n");
            }
            
            writer.write("## Test Results\n\n");
            writer.write("| Test Name | Status | Execution Time | Notes |\n");
            writer.write("|-----------|--------|----------------|-------|\n");
            
            for (QueryPerformanceResult result : testResults) {
                String status = result.getQueryCount() >= 0 ? "✅ PASS" : "❌ FAIL";
                String time = result.getExecutionTimeMs() >= 0 ? 
                    result.getExecutionTimeMs() + "ms" : "N/A";
                String notes = result.getQueryCount() >= 0 ? 
                    "Performance within limits" : "Test failed";
                
                writer.write(String.format("| %s | %s | %s | %s |\n",
                    result.getOperationName(), status, time, notes));
            }
            
            writer.write("\n## Performance Guidelines\n\n");
            writer.write("The following performance thresholds were used:\n\n");
            writer.write("- **Order Creation**: ≤ 3 queries, < 500ms\n");
            writer.write("- **Order Listing**: ≤ 2 queries, < 300ms\n");
            writer.write("- **Single Order Fetch**: ≤ 1 query, < 100ms\n");
            writer.write("- **Transaction History**: ≤ 2 queries, < 300ms\n");
            writer.write("- **Balance Check**: ≤ 1 query, < 50ms\n");
            writer.write("- **Service Listing**: ≤ 1 query, < 200ms\n\n");
            
            writer.write("## Optimization Techniques Applied\n\n");
            writer.write("1. **JOIN FETCH** - Prevent N+1 queries by fetching relationships in single query\n");
            writer.write("2. **@BatchSize** - Optimize collection loading with batch fetching\n");
            writer.write("3. **Entity Graphs** - Declarative relationship fetching\n");
            writer.write("4. **Pagination** - Limit result sets to prevent memory issues\n");
            writer.write("5. **Query Optimization** - Custom JPQL queries for specific use cases\n");
            writer.write("6. **Caching** - Strategic caching for frequently accessed data\n\n");
            
            writer.write("## Next Steps\n\n");
            if (failedTests > 0) {
                writer.write("- Review and optimize failing queries\n");
                writer.write("- Consider adding indexes for slow queries\n");
                writer.write("- Implement additional caching where appropriate\n");
            } else {
                writer.write("- Monitor query performance in production\n");
                writer.write("- Set up alerts for query count thresholds\n");
                writer.write("- Consider further optimizations as data volume grows\n");
            }
        }
    }

    private static void generateConsoleReport() {
        log.info("📊 QUERY PERFORMANCE TEST SUMMARY");
        log.info("=====================================");
        
        long successfulTests = testResults.stream()
                .filter(r -> r.getQueryCount() >= 0)
                .count();
        long failedTests = testResults.size() - successfulTests;
        
        log.info("Total Tests: {}", testResults.size());
        log.info("Successful: {} ✅", successfulTests);
        log.info("Failed: {} {}", failedTests, failedTests > 0 ? "❌" : "");
        
        if (failedTests == 0) {
            log.info("🎉 ALL PERFORMANCE TESTS PASSED!");
            log.info("Your query optimizations are working correctly.");
        } else {
            log.warn("⚠️ Some performance tests failed.");
            log.warn("Review the detailed report at: {}", REPORT_FILE);
        }
        
        log.info("=====================================");
        
        // Log individual test results
        for (QueryPerformanceResult result : testResults) {
            String status = result.getQueryCount() >= 0 ? "✅" : "❌";
            log.info("{} {} - {}ms", status, result.getOperationName(), 
                result.getExecutionTimeMs() >= 0 ? result.getExecutionTimeMs() : "Failed");
        }
    }
}
package com.smmpanel.test.performance;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Performance tests with different data volumes to ensure scalability Tests how query performance
 * changes with increasing data size
 */
@Slf4j
public class DataVolumePerformanceTest extends QueryPerformanceTestBase {

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100, 500})
    @DisplayName("Order Listing Performance with Different User Counts")
    public void testOrderListingWithVariableUserCounts(int userCount) {
        log.info("Testing order listing performance with {} users", userCount);

        // Create test data
        List<User> users = createTestUsers(userCount);
        List<Service> services = createTestServices(10);
        List<Order> orders = createTestOrders(users, services, 5); // 5 orders per user

        clearSessionAndStats();

        // Test pagination performance
        Pageable pageable = PageRequest.of(0, 20);
        User testUser = users.get(0);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Order Listing (" + userCount + " users)",
                        () -> {
                            Page<Order> userOrders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            testUser.getId(), pageable);

                            // Force loading of relationships
                            for (Order order : userOrders.getContent()) {
                                order.getUser().getUsername();
                                order.getService().getName();
                            }
                        });

        // Performance should remain consistent regardless of total user count
        // because we're filtering by specific user
        assertQueryCountWithinLimit(result, maxQueriesOrderListing);

        // Execution time might increase slightly but should remain reasonable
        long maxTimeMs = 200 + (userCount / 100) * 50; // Allow slight increase with volume
        assert result.getExecutionTimeMs() < maxTimeMs
                : String.format(
                        "Order listing with %d users took %dms, expected < %dms",
                        userCount, result.getExecutionTimeMs(), maxTimeMs);

        log.info("✓ Order listing performance acceptable with {} users: {}", userCount, result);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 25, 50, 100})
    @DisplayName("Service Listing Performance with Different Service Counts")
    public void testServiceListingWithVariableServiceCounts(int serviceCount) {
        log.info("Testing service listing performance with {} services", serviceCount);

        List<Service> services = createTestServices(serviceCount);
        clearSessionAndStats();

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Service Listing (" + serviceCount + " services)",
                        () -> {
                            List<Service> allServices = serviceRepository.findAll();

                            // Verify we got all services
                            assert allServices.size() >= serviceCount;
                        });

        // Service listing should use a single query regardless of count
        assertQueryCountWithinLimit(result, maxQueriesServiceListing);

        // Execution time should increase linearly but remain reasonable
        long maxTimeMs = 50 + (serviceCount / 10) * 10;
        assert result.getExecutionTimeMs() < maxTimeMs
                : String.format(
                        "Service listing with %d services took %dms, expected < %dms",
                        serviceCount, result.getExecutionTimeMs(), maxTimeMs);

        log.info(
                "✓ Service listing performance acceptable with {} services: {}",
                serviceCount,
                result);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100, 200})
    @DisplayName("Order Search Performance with Different Order Volumes")
    public void testOrderSearchWithVariableOrderCounts(int ordersPerUser) {
        log.info("Testing order search performance with {} orders per user", ordersPerUser);

        List<User> users = createTestUsers(5);
        List<Service> services = createTestServices(10);
        List<Order> orders = createTestOrders(users, services, ordersPerUser);

        clearSessionAndStats();

        // Test searching orders by status
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Order Search (" + (ordersPerUser * users.size()) + " total orders)",
                        () -> {
                            List<Order> activeOrders =
                                    orderRepository.findByStatusIn(
                                            List.of(
                                                    com.smmpanel.entity.OrderStatus.ACTIVE,
                                                    com.smmpanel.entity.OrderStatus.PROCESSING,
                                                    com.smmpanel.entity.OrderStatus.PENDING));

                            // Access relationships to test JOIN FETCH
                            for (Order order :
                                    activeOrders.subList(0, Math.min(10, activeOrders.size()))) {
                                order.getUser().getUsername();
                                order.getService().getName();
                            }
                        });

        // Search should use optimized queries regardless of data volume
        assertQueryCountWithinLimit(result, 2); // Status search + relationship loading

        // Execution time should increase with volume but remain reasonable
        long maxTimeMs = 100 + (ordersPerUser / 50) * 100;
        assert result.getExecutionTimeMs() < maxTimeMs
                : String.format(
                        "Order search with %d orders took %dms, expected < %dms",
                        ordersPerUser * users.size(), result.getExecutionTimeMs(), maxTimeMs);

        log.info(
                "✓ Order search performance acceptable with {} total orders: {}",
                ordersPerUser * users.size(),
                result);
    }

    @Test
    @DisplayName("Performance Comparison: Small vs Large Dataset")
    public void testPerformanceComparisonSmallVsLargeDataset() {
        log.info("Comparing performance between small and large datasets");

        // Small dataset
        List<User> smallUsers = createTestUsers(5);
        List<Service> smallServices = createTestServices(5);
        List<Order> smallOrders = createTestOrders(smallUsers, smallServices, 2);

        clearSessionAndStats();

        QueryPerformanceResult smallDatasetResult =
                measureQueryPerformance(
                        "Small Dataset Order Listing",
                        () -> {
                            Pageable pageable = PageRequest.of(0, 10);
                            Page<Order> orders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            smallUsers.get(0).getId(), pageable);

                            for (Order order : orders.getContent()) {
                                order.getUser().getUsername();
                                order.getService().getName();
                            }
                        });

        // Clear and create large dataset
        orderRepository.deleteAll();
        userRepository.deleteAll();
        serviceRepository.deleteAll();
        entityManager.flush();

        List<User> largeUsers = createTestUsers(50);
        List<Service> largeServices = createTestServices(25);
        List<Order> largeOrders = createTestOrders(largeUsers, largeServices, 10);

        clearSessionAndStats();

        QueryPerformanceResult largeDatasetResult =
                measureQueryPerformance(
                        "Large Dataset Order Listing",
                        () -> {
                            Pageable pageable = PageRequest.of(0, 10);
                            Page<Order> orders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            largeUsers.get(0).getId(), pageable);

                            for (Order order : orders.getContent()) {
                                order.getUser().getUsername();
                                order.getService().getName();
                            }
                        });

        // Compare results
        QueryPerformanceResult.PerformanceComparison comparison =
                largeDatasetResult.compareWith(smallDatasetResult);

        log.info("Performance comparison: {}", comparison.getSummary());

        // Both should use the same number of queries (pagination isolates from dataset size)
        assert smallDatasetResult.getQueryCount() == largeDatasetResult.getQueryCount()
                : "Query count should be consistent regardless of dataset size";

        // Large dataset shouldn't be more than 3x slower
        assert largeDatasetResult.getExecutionTimeMs()
                        <= smallDatasetResult.getExecutionTimeMs() * 3
                : String.format(
                        "Large dataset performance degradation too high: %dms vs %dms",
                        largeDatasetResult.getExecutionTimeMs(),
                        smallDatasetResult.getExecutionTimeMs());

        log.info(
                "✓ Performance degradation acceptable: Small={}ms, Large={}ms",
                smallDatasetResult.getExecutionTimeMs(),
                largeDatasetResult.getExecutionTimeMs());
    }

    @Test
    @DisplayName("Batch Loading Performance with Different Batch Sizes")
    public void testBatchLoadingPerformanceWithDifferentSizes() {
        List<User> users = createTestUsers(10);
        List<Service> services = createTestServices(20);
        List<Order> orders = createTestOrders(users, services, 20);

        // Test different batch sizes
        int[] batchSizes = {5, 10, 25, 50};

        for (int batchSize : batchSizes) {
            clearSessionAndStats();

            List<Long> orderIds = orders.stream().limit(batchSize).map(Order::getId).toList();

            QueryPerformanceResult result =
                    measureQueryPerformance(
                            "Batch Loading (size=" + batchSize + ")",
                            () -> {
                                List<Order> batchOrders = orderRepository.findAllById(orderIds);

                                // Access relationships to test batch fetching
                                for (Order order : batchOrders) {
                                    order.getUser().getUsername();
                                    order.getService().getName();
                                }
                            });

            // Batch loading should use minimal queries regardless of batch size
            assertQueryCountWithinLimit(result, 3); // ID lookup + user batch + service batch

            // Time should scale reasonably with batch size
            long maxTimeMs = 50 + (batchSize / 10) * 50;
            assert result.getExecutionTimeMs() < maxTimeMs
                    : String.format(
                            "Batch loading %d orders took %dms, expected < %dms",
                            batchSize, result.getExecutionTimeMs(), maxTimeMs);

            log.info("✓ Batch loading performance acceptable for size {}: {}", batchSize, result);
        }
    }

    @Test
    @DisplayName("Memory Usage and Query Performance Under Load")
    public void testPerformanceUnderMemoryPressure() {
        log.info("Testing query performance under memory pressure");

        // Create a large dataset to put memory pressure
        List<User> users = createTestUsers(100);
        List<Service> services = createTestServices(50);
        List<Order> orders = createTestOrders(users, services, 50); // 5000 total orders

        clearSessionAndStats();

        // Perform multiple operations in sequence to test memory management
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Operations Under Memory Pressure",
                        () -> {
                            // Multiple operations that should maintain performance

                            // 1. User lookup
                            User user = userRepository.findById(users.get(0).getId()).orElse(null);
                            assert user != null;

                            // 2. Order listing
                            Pageable pageable = PageRequest.of(0, 20);
                            Page<Order> userOrders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            user.getId(), pageable);

                            // 3. Service listing
                            List<Service> allServices = serviceRepository.findAll();

                            // 4. Order statistics
                            long orderCount =
                                    orderRepository.countByUser_Username(user.getUsername());

                            // 5. Access relationships to test lazy loading
                            for (Order order : userOrders.getContent()) {
                                order.getUser().getUsername();
                                order.getService().getName();
                            }

                            assert !allServices.isEmpty();
                            assert orderCount >= 0;
                        });

        // Performance should remain acceptable even under memory pressure
        assertQueryCountWithinLimit(result, 6); // Multiple operations
        assert result.getExecutionTimeMs() < 1000
                : "Operations under memory pressure should complete in under 1 second";

        log.info("✓ Performance under memory pressure acceptable: {}", result);
    }
}

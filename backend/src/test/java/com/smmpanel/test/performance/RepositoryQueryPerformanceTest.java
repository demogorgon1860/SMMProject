package com.smmpanel.test.performance;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Specific tests for repository query performance and optimization Focuses on testing the optimized
 * repository methods we've implemented
 */
@Slf4j
public class RepositoryQueryPerformanceTest extends QueryPerformanceTestBase {

    private List<User> testUsers;
    private List<Service> testServices;
    private List<Order> testOrders;

    @BeforeEach
    public void setupRepositoryTestData() {
        testUsers = createTestUsers(10);
        testServices = createTestServices(15);
        testOrders = createTestOrders(testUsers, testServices, 8);
        clearSessionAndStats();
    }

    @Test
    @DisplayName("Optimized Order Repository - Find with Details Performance")
    public void testOptimizedOrderRepositoryFindWithDetails() {
        Order testOrder = testOrders.get(0);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "OrderRepository.findByIdWithAllDetails",
                        () -> {
                            Optional<Order> order =
                                    orderRepository.findByIdWithAllDetails(testOrder.getId());

                            assert order.isPresent();

                            // Access all relationships - should NOT trigger additional queries
                            Order o = order.get();
                            String username = o.getUser().getUsername();
                            String serviceName = o.getService().getName();

                            // These should be loaded by JOIN FETCH
                            if (o.getBinomCampaigns() != null) {
                                o.getBinomCampaigns().size();
                            }

                            if (o.getVideoProcessing() != null) {
                                o.getVideoProcessing().getStatus();
                            }

                            assert username != null;
                            assert serviceName != null;
                        });

        // Should use exactly 1 query with all JOINs
        assertQueryCountWithinLimit(result, 1);
        assert result.getExecutionTimeMs() < 50;

        log.info("✓ Optimized single order fetch: {}", result);
    }

    @Test
    @DisplayName("Optimized Order Repository - User Orders with Pagination")
    public void testOptimizedUserOrdersWithPagination() {
        User testUser = testUsers.get(0);
        Pageable pageable = PageRequest.of(0, 5);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "OrderRepository.findOrdersWithDetailsByUserId",
                        () -> {
                            Page<Order> orders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            testUser.getId(), pageable);

                            // Access relationships for all orders - should NOT trigger N+1
                            for (Order order : orders.getContent()) {
                                String username = order.getUser().getUsername();
                                String serviceName = order.getService().getName();
                                assert username != null;
                                assert serviceName != null;
                            }
                        });

        // Should use at most 2 queries: data query + count query for pagination
        assertQueryCountWithinLimit(result, 2);
        assert result.getExecutionTimeMs() < 100;

        log.info("✓ Optimized paginated user orders: {}", result);
    }

    @Test
    @DisplayName("Repository Batch Fetching - Multiple Orders by ID")
    public void testRepositoryBatchFetching() {
        List<Long> orderIds = testOrders.stream().limit(10).map(Order::getId).toList();

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "OrderRepository.findAllById (batch)",
                        () -> {
                            List<Order> orders = orderRepository.findAllById(orderIds);

                            // Access relationships - should use batch fetching
                            for (Order order : orders) {
                                String username = order.getUser().getUsername();
                                String serviceName = order.getService().getName();
                                assert username != null;
                                assert serviceName != null;
                            }
                        });

        // Should use minimal queries due to batch fetching configuration
        assertQueryCountWithinLimit(result, 3); // Orders + Users batch + Services batch
        assert result.getExecutionTimeMs() < 150;

        log.info("✓ Repository batch fetching: {}", result);
    }

    @Test
    @DisplayName("Repository Query - Orders by Status with Details")
    public void testOrdersByStatusWithDetails() {
        List<OrderStatus> statuses =
                List.of(OrderStatus.ACTIVE, OrderStatus.PROCESSING, OrderStatus.PENDING);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "OrderRepository.findByStatusIn with relationships",
                        () -> {
                            List<Order> orders = orderRepository.findByStatusIn(statuses);

                            // Access relationships - should be fetched by JOIN FETCH
                            for (Order order : orders.subList(0, Math.min(5, orders.size()))) {
                                String username = order.getUser().getUsername();
                                String serviceName = order.getService().getName();
                                assert username != null;
                                assert serviceName != null;
                            }
                        });

        // Should use 1 query with JOIN FETCH
        assertQueryCountWithinLimit(result, 1);
        assert result.getExecutionTimeMs() < 200;

        log.info("✓ Orders by status with relationships: {}", result);
    }

    @Test
    @DisplayName("Repository Query - Active Orders for Processing")
    public void testActiveOrdersForProcessing() {
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "OrderRepository.findActiveOrdersWithDetails",
                        () -> {
                            List<Order> activeOrders =
                                    orderRepository.findActiveOrdersWithDetails();

                            // This query is used by processing services - must be efficient
                            for (Order order :
                                    activeOrders.subList(0, Math.min(3, activeOrders.size()))) {
                                String username = order.getUser().getUsername();
                                String serviceName = order.getService().getName();

                                // Access video processing if exists
                                if (order.getVideoProcessing() != null) {
                                    order.getVideoProcessing().getStatus();
                                }

                                assert username != null;
                                assert serviceName != null;
                            }
                        });

        // Critical for processing performance - must be 1 query
        assertQueryCountWithinLimit(result, 1);
        assert result.getExecutionTimeMs() < 100;

        log.info("✓ Active orders for processing: {}", result);
    }

    @Test
    @DisplayName("Repository Query - User Statistics Aggregation")
    public void testUserStatisticsAggregation() {
        User testUser = testUsers.get(0);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "User Statistics Aggregation",
                        () -> {
                            // Common dashboard queries
                            Long orderCount =
                                    orderRepository.countByUser_Username(testUser.getUsername());
                            var totalSpent =
                                    orderRepository.sumChargeByUser_Username(
                                            testUser.getUsername());
                            Long pendingCount =
                                    orderRepository.countByUser_UsernameAndStatus(
                                            testUser.getUsername(), OrderStatus.PENDING);
                            var recentSpent =
                                    orderRepository.sumChargeByUser_UsernameAndCreatedAtAfter(
                                            testUser.getUsername(),
                                            LocalDateTime.now().minusDays(30));

                            assert orderCount != null;
                            assert totalSpent != null;
                            assert pendingCount != null;
                            assert recentSpent != null;
                        });

        // Aggregation queries should be efficient
        assertQueryCountWithinLimit(result, 4); // 4 separate aggregation queries
        assert result.getExecutionTimeMs() < 200;

        log.info("✓ User statistics aggregation: {}", result);
    }

    @Test
    @DisplayName("Repository Query - Error Recovery Orders")
    public void testErrorRecoveryOrders() {
        Pageable pageable = PageRequest.of(0, 10);
        LocalDateTime now = LocalDateTime.now();

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Error Recovery Queries",
                        () -> {
                            // Orders ready for retry
                            Page<Order> retryOrders =
                                    orderRepository.findOrdersReadyForRetry(now, pageable);

                            // Dead letter queue orders
                            Page<Order> dlqOrders =
                                    orderRepository.findDeadLetterQueueOrders(pageable);

                            // Error statistics
                            List<Object[]> errorStats = orderRepository.getErrorTypeStatistics();

                            // Access relationships for retry orders
                            for (Order order : retryOrders.getContent()) {
                                String username = order.getUser().getUsername();
                                String serviceName = order.getService().getName();
                                assert username != null;
                                assert serviceName != null;
                            }

                            assert dlqOrders != null;
                            assert errorStats != null;
                        });

        // Error recovery queries should be optimized for admin operations
        assertQueryCountWithinLimit(result, 5); // Multiple queries but optimized with JOIN FETCH
        assert result.getExecutionTimeMs() < 300;

        log.info("✓ Error recovery queries: {}", result);
    }

    @Test
    @DisplayName("Repository Performance - Complex Search Scenarios")
    public void testComplexSearchScenarios() {
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Complex Search Scenarios",
                        () -> {
                            Pageable pageable = PageRequest.of(0, 5);

                            // Search by multiple criteria
                            List<OrderStatus> activeStatuses =
                                    List.of(OrderStatus.ACTIVE, OrderStatus.PROCESSING);
                            List<Order> activeOrders =
                                    orderRepository.findByStatusIn(activeStatuses);

                            // Orders created in last 7 days
                            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
                            List<Order> recentOrders =
                                    orderRepository.findOrdersCreatedAfter(weekAgo);

                            // Orders by error type (if any errors exist)
                            List<Object[]> errorTypes = orderRepository.getErrorTypeStatistics();
                            if (!errorTypes.isEmpty()) {
                                String errorType = (String) errorTypes.get(0)[0];
                                Page<Order> errorOrders =
                                        orderRepository.findOrdersByErrorType(errorType, pageable);

                                // Access relationships
                                for (Order order : errorOrders.getContent()) {
                                    order.getUser().getUsername();
                                    order.getService().getName();
                                }
                            }

                            // Verify results
                            assert activeOrders != null;
                            assert recentOrders != null;
                        });

        // Complex searches should still be efficient with proper indexing
        assertQueryCountWithinLimit(result, 6); // Multiple search queries
        assert result.getExecutionTimeMs() < 400;

        log.info("✓ Complex search scenarios: {}", result);
    }

    @Test
    @DisplayName("Repository Performance - Concurrent Access Simulation")
    public void testRepositoryConcurrentAccessSimulation() {
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Simulated Concurrent Repository Access",
                        () -> {
                            // Simulate multiple concurrent operations
                            User user1 = testUsers.get(0);
                            User user2 = testUsers.get(1);

                            // Operation 1: User 1 order listing
                            Pageable pageable1 = PageRequest.of(0, 5);
                            Page<Order> user1Orders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            user1.getId(), pageable1);

                            // Operation 2: User 2 order listing
                            Pageable pageable2 = PageRequest.of(0, 5);
                            Page<Order> user2Orders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            user2.getId(), pageable2);

                            // Operation 3: Global order statistics
                            long totalOrders = orderRepository.count();
                            long pendingOrders = orderRepository.countByStatus(OrderStatus.PENDING);

                            // Operation 4: Service lookup
                            List<Service> services = serviceRepository.findAll();

                            // Access relationships to test optimization
                            for (Order order : user1Orders.getContent()) {
                                order.getUser().getUsername();
                                order.getService().getName();
                            }

                            for (Order order : user2Orders.getContent()) {
                                order.getUser().getUsername();
                                order.getService().getName();
                            }

                            assert totalOrders >= 0;
                            assert pendingOrders >= 0;
                            assert !services.isEmpty();
                        });

        // Concurrent operations should remain efficient
        assertQueryCountWithinLimit(result, 8); // Multiple operations with optimizations
        assert result.getExecutionTimeMs() < 500;

        log.info("✓ Simulated concurrent access: {}", result);
    }
}

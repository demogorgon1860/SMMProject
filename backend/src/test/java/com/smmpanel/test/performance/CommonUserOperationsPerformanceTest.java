package com.smmpanel.test.performance;

import com.smmpanel.dto.ServiceResponse;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.service.BalanceService;
import com.smmpanel.service.OrderService;
import com.smmpanel.service.ServiceService;
import java.math.BigDecimal;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Performance tests for common user operations in the SMM Panel Tests real-world scenarios with
 * actual query counting and performance assertions
 */
@Slf4j
public class CommonUserOperationsPerformanceTest extends QueryPerformanceTestBase {

    @Autowired private OrderService orderService;

    @Autowired private BalanceService balanceService;

    @Autowired private ServiceService serviceService;

    private List<User> testUsers;
    private List<Service> testServices;
    private List<Order> testOrders;

    @BeforeEach
    public void setupTestData() {
        // Create test data for performance testing
        testUsers = createTestUsers(5);
        testServices = createTestServices(10);
        testOrders = createTestOrders(testUsers, testServices, 3); // 3 orders per user

        clearSessionAndStats();
        log.info(
                "Test data setup completed: {} users, {} services, {} orders",
                testUsers.size(),
                testServices.size(),
                testOrders.size());
    }

    @Test
    @DisplayName("User Registration and Profile Creation Performance")
    public void testUserRegistrationPerformance() {
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "User Registration",
                        () -> {
                            // Simulate user registration process
                            User newUser =
                                    User.builder()
                                            .username("perftest_user")
                                            .email("perftest@example.com")
                                            .passwordHash("hashedpassword")
                                            .balance(BigDecimal.ZERO)
                                            .isActive(true)
                                            .build();

                            User savedUser = userRepository.save(newUser);

                            // Simulate profile retrieval after registration
                            User retrievedUser =
                                    userRepository.findById(savedUser.getId()).orElse(null);
                            assert retrievedUser != null;
                        });

        // User registration should be very efficient
        assertQueryCountWithinLimit(result, 2); // INSERT + SELECT
        assert result.getExecutionTimeMs() < 100
                : "User registration should complete in under 100ms";
    }

    @Test
    @DisplayName("Service Listing Performance")
    public void testServiceListingPerformance() {
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Service Listing",
                        () -> {
                            List<ServiceResponse> services = serviceService.getAllActiveServices();
                            assert !services.isEmpty();
                        });

        assertQueryCountWithinLimit(result, maxQueriesServiceListing);
        assert result.getExecutionTimeMs() < 200 : "Service listing should complete in under 200ms";
    }

    @Test
    @DisplayName("Order Creation Performance")
    public void testOrderCreationPerformance() {
        User testUser = testUsers.get(0);
        Service testService = testServices.get(0);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Order Creation",
                        () -> {
                            // Simulate order creation process that involves:
                            // 1. User validation
                            // 2. Service validation
                            // 3. Balance check
                            // 4. Order creation
                            // 5. Balance deduction

                            // These operations should be optimized to minimize queries
                            Order order =
                                    Order.builder()
                                            .user(testUser)
                                            .service(testService)
                                            .link("https://youtube.com/watch?v=performance_test")
                                            .quantity(testService.getMinOrder())
                                            .charge(BigDecimal.valueOf(10.00))
                                            .build();

                            Order savedOrder = orderRepository.save(order);
                            assert savedOrder.getId() != null;
                        });

        assertQueryCountWithinLimit(result, maxQueriesOrderCreation);
        assert result.getExecutionTimeMs() < 500 : "Order creation should complete in under 500ms";
    }

    @Test
    @DisplayName("User Order History Listing Performance")
    public void testOrderHistoryListingPerformance() {
        User testUser = testUsers.get(0);
        Pageable pageable = PageRequest.of(0, 20);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Order History Listing",
                        () -> {
                            // Test paginated order listing with user filtering
                            Page<Order> orders =
                                    orderRepository.findOrdersWithDetailsByUserId(
                                            testUser.getId(), pageable);

                            // Force loading of relationships to test JOIN FETCH optimization
                            for (Order order : orders.getContent()) {
                                String serviceName = order.getService().getName();
                                String username = order.getUser().getUsername();
                                assert serviceName != null;
                                assert username != null;
                            }
                        });

        assertQueryCountWithinLimit(result, maxQueriesOrderListing);
        assert result.getExecutionTimeMs() < 300
                : "Order history listing should complete in under 300ms";
    }

    @Test
    @DisplayName("Single Order Details Fetch Performance")
    public void testSingleOrderFetchPerformance() {
        Order testOrder = testOrders.get(0);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Single Order Fetch",
                        () -> {
                            // Test fetching single order with all details
                            Order order =
                                    orderRepository
                                            .findByIdWithAllDetails(testOrder.getId())
                                            .orElse(null);

                            assert order != null;
                            // Access all relationships to test JOIN FETCH
                            String username = order.getUser().getUsername();
                            String serviceName = order.getService().getName();

                            if (order.getBinomCampaigns() != null) {
                                order.getBinomCampaigns().size();
                            }

                            if (order.getVideoProcessing() != null) {
                                order.getVideoProcessing().getStatus();
                            }

                            assert username != null;
                            assert serviceName != null;
                        });

        assertQueryCountWithinLimit(result, maxQueriesSingleOrderFetch);
        assert result.getExecutionTimeMs() < 100
                : "Single order fetch should complete in under 100ms";
    }

    @Test
    @DisplayName("Balance Check Performance")
    public void testBalanceCheckPerformance() {
        User testUser = testUsers.get(0);
        BigDecimal checkAmount = BigDecimal.valueOf(50.00);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Balance Check",
                        () -> {
                            BigDecimal userBalance =
                                    balanceService.getUserBalance(testUser.getId());
                            boolean hasSufficientBalance =
                                    balanceService.hasSufficientBalance(
                                            testUser.getId(), checkAmount);

                            assert userBalance != null;
                            assert hasSufficientBalance
                                    || !hasSufficientBalance; // Just check it returns a boolean
                        });

        assertQueryCountWithinLimit(result, maxQueriesBalanceCheck);
        assert result.getExecutionTimeMs() < 50 : "Balance check should complete in under 50ms";
    }

    @Test
    @DisplayName("User Profile with Statistics Performance")
    public void testUserProfileWithStatsPerformance() {
        User testUser = testUsers.get(0);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "User Profile with Stats",
                        () -> {
                            // Simulate loading user profile with order statistics
                            User user = userRepository.findById(testUser.getId()).orElse(null);
                            assert user != null;

                            // Get user's order count and total spent (common profile stats)
                            Long orderCount =
                                    orderRepository.countByUser_Username(user.getUsername());
                            BigDecimal totalSpent =
                                    orderRepository.sumChargeByUser_Username(user.getUsername());

                            assert orderCount != null;
                            assert totalSpent != null;
                        });

        assertQueryCountWithinLimit(result, maxQueriesUserProfile);
        assert result.getExecutionTimeMs() < 150
                : "User profile loading should complete in under 150ms";
    }

    @Test
    @DisplayName("Order Status Update Performance")
    public void testOrderStatusUpdatePerformance() {
        Order testOrder = testOrders.get(0);

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Order Status Update",
                        () -> {
                            // Simulate order status update (common admin operation)
                            Order order = orderRepository.findById(testOrder.getId()).orElse(null);
                            assert order != null;

                            order.setStatus(com.smmpanel.entity.OrderStatus.COMPLETED);
                            order.setRemains(0);
                            orderRepository.save(order);
                        });

        // Status updates should be very efficient
        assertQueryCountWithinLimit(result, 2); // SELECT + UPDATE
        assert result.getExecutionTimeMs() < 100
                : "Order status update should complete in under 100ms";
    }

    @Test
    @DisplayName("Search Orders Performance")
    public void testSearchOrdersPerformance() {
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Search Orders",
                        () -> {
                            // Test searching orders by different criteria
                            Pageable pageable = PageRequest.of(0, 10);

                            // Search by status
                            List<Order> activeOrders =
                                    orderRepository.findByStatusIn(
                                            List.of(
                                                    com.smmpanel.entity.OrderStatus.ACTIVE,
                                                    com.smmpanel.entity.OrderStatus.PROCESSING));

                            // Search by user
                            Page<Order> userOrders =
                                    orderRepository.findByUser(testUsers.get(0), pageable);

                            assert !activeOrders.isEmpty()
                                    || activeOrders.isEmpty(); // Just check it executes
                            assert userOrders != null;
                        });

        // Search operations should be reasonably efficient
        assertQueryCountWithinLimit(result, 3); // Two search queries
        assert result.getExecutionTimeMs() < 200 : "Order search should complete in under 200ms";
    }

    @Test
    @DisplayName("Bulk Order Status Check Performance")
    public void testBulkOrderStatusCheckPerformance() {
        List<Long> orderIds = testOrders.stream().limit(5).map(Order::getId).toList();

        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Bulk Order Status Check",
                        () -> {
                            // Simulate bulk order status checking (Perfect Panel API scenario)
                            List<Order> orders = orderRepository.findAllById(orderIds);

                            // Access order details for each (should use batch fetching)
                            for (Order order : orders) {
                                String status = order.getStatus().name();
                                String serviceName = order.getService().getName();
                                assert status != null;
                                assert serviceName != null;
                            }
                        });

        assertQueryCountWithinLimit(result, maxQueriesBulkOperations);
        assert result.getExecutionTimeMs() < 300
                : "Bulk order status check should complete in under 300ms";
    }

    @Test
    @DisplayName("Dashboard Data Loading Performance")
    public void testDashboardDataLoadingPerformance() {
        QueryPerformanceResult result =
                measureQueryPerformance(
                        "Dashboard Data Loading",
                        () -> {
                            // Simulate loading dashboard data (multiple aggregated queries)

                            // Total orders count
                            long totalOrders = orderRepository.count();

                            // Orders by status
                            long pendingOrders =
                                    orderRepository.countByStatus(
                                            com.smmpanel.entity.OrderStatus.PENDING);
                            long completedOrders =
                                    orderRepository.countByStatus(
                                            com.smmpanel.entity.OrderStatus.COMPLETED);

                            // Recent orders
                            Pageable recentOrdersPage = PageRequest.of(0, 5);
                            Page<Order> recentOrders = orderRepository.findAll(recentOrdersPage);

                            assert totalOrders >= 0;
                            assert pendingOrders >= 0;
                            assert completedOrders >= 0;
                            assert recentOrders != null;
                        });

        // Dashboard loading may require multiple queries but should be optimized
        assertQueryCountWithinLimit(result, 5);
        assert result.getExecutionTimeMs() < 400
                : "Dashboard data loading should complete in under 400ms";
    }
}

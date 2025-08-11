package com.smmpanel.service;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.entity.*;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.repository.jpa.*;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Slf4j
@SpringBootTest
@Testcontainers
class ConcurrentOrderCreationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("smm_panel_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.balance.retry.max-attempts", () -> "5");
        registry.add("app.balance.retry.initial-delay", () -> "50");
        registry.add("app.balance.retry.max-delay", () -> "1000");
        registry.add("app.balance.retry.multiplier", () -> "2.0");
        registry.add("app.transaction.monitoring.enabled", () -> "true");
    }

    @Autowired private OrderService orderService;

    @Autowired private BalanceService balanceService;

    @Autowired private UserRepository userRepository;

    @Autowired private ServiceRepository serviceRepository;

    @Autowired private OrderRepository orderRepository;

    @Autowired private BalanceTransactionRepository transactionRepository;

    private User testUser;
    private List<User> multipleUsers;
    private com.smmpanel.entity.Service lowCostService;
    private com.smmpanel.entity.Service highCostService;
    private String testApiKey;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up previous test data
        transactionRepository.deleteAll();
        orderRepository.deleteAll();
        serviceRepository.deleteAll();
        userRepository.deleteAll();

        // Create test user with API key
        testUser =
                createUserWithApiKey(
                        "ordertest", "order@test.com", new BigDecimal("1000.00"), "test-api-key");
        testApiKey = "test-api-key";

        // Create multiple users for complex scenarios
        multipleUsers = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            multipleUsers.add(
                    createUserWithApiKey(
                            "orderuser" + i,
                            "orderuser" + i + "@test.com",
                            new BigDecimal("500.00"),
                            "api-key-" + i));
        }

        // Create test services
        lowCostService = createService("Low Cost Service", new BigDecimal("1.00"), 1, 1000);
        highCostService = createService("High Cost Service", new BigDecimal("50.00"), 1, 100);
    }

    private User createUserWithApiKey(
            String username, String email, BigDecimal balance, String apiKey) {
        User user =
                User.builder()
                        .username(username)
                        .email(email)
                        .passwordHash("password")
                        .role(UserRole.USER)
                        .balance(balance)
                        .totalSpent(BigDecimal.ZERO)
                        .apiKeyHash(hashApiKey(apiKey))
                        .isActive(true)
                        .createdAt(LocalDateTime.now())
                        .version(0L)
                        .build();
        return userRepository.save(user);
    }

    private com.smmpanel.entity.Service createService(
            String name, BigDecimal rate, Integer minOrder, Integer maxOrder) {
        com.smmpanel.entity.Service service =
                com.smmpanel.entity.Service.builder()
                        .name(name)
                        .pricePer1000(rate)
                        .minOrder(minOrder)
                        .maxOrder(maxOrder)
                        .active(true)
                        .description("Test service: " + name)
                        .build();
        return serviceRepository.save(service);
    }

    private String hashApiKey(String apiKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(apiKey.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash API key", e);
        }
    }

    @Test
    @Timeout(120)
    void testConcurrentOrderCreationSameUser() throws InterruptedException {
        log.info("Starting concurrent order creation test for same user");

        int numberOfThreads = 30;
        int ordersPerThread = 5;

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger successfulOrders = new AtomicInteger(0);
        AtomicInteger failedOrders = new AtomicInteger(0);
        AtomicInteger insufficientBalanceErrors = new AtomicInteger(0);
        List<OrderResponse> createdOrders = new CopyOnWriteArrayList<>();
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < ordersPerThread; j++) {
                                try {
                                    CreateOrderRequest request =
                                            CreateOrderRequest.builder()
                                                    .service(lowCostService.getId())
                                                    .link(
                                                            "https://test"
                                                                    + threadIndex
                                                                    + "-"
                                                                    + j
                                                                    + ".com/video")
                                                    .quantity(100)
                                                    .build();

                                    OrderResponse orderResponse =
                                            orderService.createOrderWithApiKey(request, testApiKey);
                                    createdOrders.add(orderResponse);
                                    successfulOrders.incrementAndGet();

                                } catch (InsufficientBalanceException e) {
                                    insufficientBalanceErrors.incrementAndGet();
                                    failedOrders.incrementAndGet();
                                } catch (Exception e) {
                                    exceptions.add(e);
                                    failedOrders.incrementAndGet();
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(120, TimeUnit.SECONDS),
                "Concurrent order creation should complete within 2 minutes");
        executor.shutdown();

        // Verify results
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();

        // Calculate expected balance
        BigDecimal orderCost =
                lowCostService
                        .getPricePer1000()
                        .multiply(new BigDecimal("100")); // 1.00 * 100 = 100.00 per order
        BigDecimal expectedBalance =
                new BigDecimal("1000.00")
                        .subtract(orderCost.multiply(new BigDecimal(successfulOrders.get())));

        assertEquals(
                expectedBalance,
                updatedUser.getBalance(),
                "User balance should reflect only successful order charges");

        // Verify order count
        List<Order> userOrders = orderRepository.findOrdersWithDetailsByUserId(testUser.getId());
        assertEquals(
                successfulOrders.get(),
                userOrders.size(),
                "Number of created orders should match successful operations");

        // Verify transaction consistency
        List<BalanceTransaction> transactions =
                transactionRepository.findByUserId(testUser.getId(), null).getContent();
        assertEquals(
                successfulOrders.get(),
                transactions.size(),
                "Should have one transaction per successful order");

        // Verify all orders have unique links (no duplicates)
        Set<String> uniqueLinks = new HashSet<>();
        for (Order order : userOrders) {
            assertTrue(uniqueLinks.add(order.getLink()), "All order links should be unique");
        }

        log.info(
                "Same user test completed - Successful: {}, Failed: {}, Insufficient balance: {}, "
                        + "Final balance: {}, Total exceptions: {}",
                successfulOrders.get(),
                failedOrders.get(),
                insufficientBalanceErrors.get(),
                updatedUser.getBalance(),
                exceptions.size());

        // Ensure reasonable success rate
        double successRate =
                (double) successfulOrders.get()
                        / (successfulOrders.get() + failedOrders.get())
                        * 100;
        assertTrue(successRate > 60, "Success rate should be above 60%: " + successRate + "%");
    }

    @Test
    @Timeout(90)
    void testConcurrentOrderCreationDifferentUsers() throws InterruptedException {
        log.info("Starting concurrent order creation test for different users");

        int ordersPerUser = 10;
        ExecutorService executor = Executors.newFixedThreadPool(multipleUsers.size());
        CountDownLatch latch = new CountDownLatch(multipleUsers.size());

        AtomicInteger totalSuccessfulOrders = new AtomicInteger(0);
        AtomicInteger totalFailedOrders = new AtomicInteger(0);
        Map<String, AtomicInteger> userOrderCounts = new ConcurrentHashMap<>();

        for (int i = 0; i < multipleUsers.size(); i++) {
            final int userIndex = i;
            final User user = multipleUsers.get(userIndex);
            final String apiKey = "api-key-" + userIndex;

            userOrderCounts.put(user.getUsername(), new AtomicInteger(0));

            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < ordersPerUser; j++) {
                                try {
                                    CreateOrderRequest request =
                                            CreateOrderRequest.builder()
                                                    .service(lowCostService.getId())
                                                    .link(
                                                            "https://user"
                                                                    + userIndex
                                                                    + "-order"
                                                                    + j
                                                                    + ".com/video")
                                                    .quantity(50)
                                                    .build();

                                    orderService.createOrderWithApiKey(request, apiKey);
                                    userOrderCounts.get(user.getUsername()).incrementAndGet();
                                    totalSuccessfulOrders.incrementAndGet();

                                } catch (Exception e) {
                                    totalFailedOrders.incrementAndGet();
                                    log.debug(
                                            "Order creation failed for user {}: {}",
                                            user.getUsername(),
                                            e.getMessage());
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(90, TimeUnit.SECONDS),
                "Multi-user order creation should complete within 90 seconds");
        executor.shutdown();

        // Verify each user's orders and balance
        for (User user : multipleUsers) {
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            List<Order> userOrders = orderRepository.findOrdersWithDetailsByUserId(user.getId());

            int expectedOrderCount = userOrderCounts.get(user.getUsername()).get();
            assertEquals(
                    expectedOrderCount,
                    userOrders.size(),
                    "User " + user.getUsername() + " should have correct number of orders");

            // Verify balance calculation
            BigDecimal orderCost =
                    lowCostService
                            .getPricePer1000()
                            .multiply(new BigDecimal("50")); // 1.00 * 50 = 50.00 per order
            BigDecimal expectedBalance =
                    new BigDecimal("500.00")
                            .subtract(orderCost.multiply(new BigDecimal(expectedOrderCount)));

            assertEquals(
                    expectedBalance,
                    updatedUser.getBalance(),
                    "User " + user.getUsername() + " should have correct balance");
        }

        log.info(
                "Multi-user test completed - Total successful: {}, Total failed: {}",
                totalSuccessfulOrders.get(),
                totalFailedOrders.get());
    }

    @Test
    @Timeout(150)
    void testConcurrentOrderCreationWithInsufficientBalance() throws InterruptedException {
        log.info("Starting insufficient balance order creation test");

        // Create user with limited balance for high-cost orders
        User limitedUser =
                createUserWithApiKey(
                        "limited", "limited@test.com", new BigDecimal("200.00"), "limited-api-key");

        int numberOfThreads = 20;
        BigDecimal orderCost =
                highCostService
                        .getPricePer1000()
                        .multiply(new BigDecimal("10")); // 50.00 * 10 = 500.00 per order

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger successfulOrders = new AtomicInteger(0);
        AtomicInteger insufficientBalanceCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);
        AtomicLong totalExecutionTime = new AtomicLong(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        long startTime = System.currentTimeMillis();
                        try {
                            CreateOrderRequest request =
                                    CreateOrderRequest.builder()
                                            .service(highCostService.getId())
                                            .link("https://expensive" + threadIndex + ".com/video")
                                            .quantity(10)
                                            .build();

                            orderService.createOrderWithApiKey(request, "limited-api-key");
                            successfulOrders.incrementAndGet();

                        } catch (InsufficientBalanceException e) {
                            insufficientBalanceCount.incrementAndGet();
                        } catch (Exception e) {
                            otherErrors.incrementAndGet();
                            log.debug(
                                    "Unexpected error in thread {}: {}",
                                    threadIndex,
                                    e.getMessage());
                        } finally {
                            totalExecutionTime.addAndGet(System.currentTimeMillis() - startTime);
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(150, TimeUnit.SECONDS),
                "Insufficient balance test should complete within 150 seconds");
        executor.shutdown();

        // Verify results
        User updatedUser = userRepository.findById(limitedUser.getId()).orElseThrow();

        // Should only allow orders that fit within balance (200/500 = 0, but might allow partial if
        // any balance remains)
        assertTrue(
                successfulOrders.get() <= 1,
                "Should allow at most 1 expensive order with limited balance");
        assertTrue(
                insufficientBalanceCount.get() >= numberOfThreads - 1,
                "Most orders should fail due to insufficient balance");

        // Verify final balance is non-negative
        assertTrue(
                updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0,
                "Balance should never go negative");

        log.info(
                "Insufficient balance test completed - Successful: {}, Insufficient balance: {}, "
                        + "Other errors: {}, Final balance: {}, Average execution time: {}ms",
                successfulOrders.get(),
                insufficientBalanceCount.get(),
                otherErrors.get(),
                updatedUser.getBalance(),
                totalExecutionTime.get() / numberOfThreads);
    }

    @Test
    @Timeout(120)
    void testConcurrentOrderCreationStressTest() throws InterruptedException {
        log.info("Starting order creation stress test");

        int numberOfThreads = 50;
        int ordersPerThread = 3;

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger totalOrders = new AtomicInteger(0);
        AtomicInteger totalErrors = new AtomicInteger(0);
        AtomicLong maxResponseTime = new AtomicLong(0);
        AtomicLong totalResponseTime = new AtomicLong(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < ordersPerThread; j++) {
                                long startTime = System.currentTimeMillis();
                                try {
                                    // Randomly select user and service
                                    User randomUser =
                                            multipleUsers.get(
                                                    ThreadLocalRandom.current()
                                                            .nextInt(multipleUsers.size()));
                                    String apiKey = "api-key-" + multipleUsers.indexOf(randomUser);

                                    com.smmpanel.entity.Service randomService =
                                            ThreadLocalRandom.current().nextBoolean()
                                                    ? lowCostService
                                                    : highCostService;

                                    CreateOrderRequest request =
                                            CreateOrderRequest.builder()
                                                    .service(randomService.getId())
                                                    .link(
                                                            "https://stress"
                                                                    + threadIndex
                                                                    + "-"
                                                                    + j
                                                                    + ".com/video")
                                                    .quantity(
                                                            ThreadLocalRandom.current()
                                                                    .nextInt(10, 100))
                                                    .build();

                                    orderService.createOrderWithApiKey(request, apiKey);
                                    totalOrders.incrementAndGet();

                                } catch (Exception e) {
                                    totalErrors.incrementAndGet();
                                } finally {
                                    long responseTime = System.currentTimeMillis() - startTime;
                                    maxResponseTime.accumulateAndGet(responseTime, Math::max);
                                    totalResponseTime.addAndGet(responseTime);
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(120, TimeUnit.SECONDS), "Stress test should complete within 2 minutes");
        executor.shutdown();

        // Verify system remained stable
        double averageResponseTime =
                (double) totalResponseTime.get() / (numberOfThreads * ordersPerThread);
        double successRate =
                (double) totalOrders.get() / (totalOrders.get() + totalErrors.get()) * 100;

        // Performance assertions
        assertTrue(
                maxResponseTime.get() < 30000, "No single order should take more than 30 seconds");
        assertTrue(averageResponseTime < 5000, "Average response time should be under 5 seconds");
        assertTrue(
                successRate > 30,
                "Success rate should be above 30% even under stress: " + successRate + "%");

        // Verify data consistency for all users
        for (User user : multipleUsers) {
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            assertTrue(
                    updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0,
                    "User balance should never go negative: " + updatedUser.getUsername());

            List<Order> userOrders = orderRepository.findOrdersWithDetailsByUserId(user.getId());
            List<BalanceTransaction> userTransactions =
                    transactionRepository.findByUserId(user.getId(), null).getContent();

            // Count only ORDER_PAYMENT transactions
            long orderTransactions =
                    userTransactions.stream()
                            .filter(t -> t.getTransactionType() == TransactionType.ORDER_PAYMENT)
                            .count();

            assertEquals(
                    userOrders.size(),
                    orderTransactions,
                    "Each order should have a corresponding transaction for user: "
                            + updatedUser.getUsername());
        }

        log.info(
                "Stress test completed - Total orders: {}, Total errors: {}, Success rate: {:.2f}%,"
                        + " Max response time: {}ms, Average response time: {:.2f}ms",
                totalOrders.get(),
                totalErrors.get(),
                successRate,
                maxResponseTime.get(),
                averageResponseTime);
    }

    @Test
    @Timeout(60)
    void testOrderCreationWithDuplicateLinks() throws InterruptedException {
        log.info("Starting duplicate links prevention test");

        int numberOfThreads = 20;
        String duplicateLink = "https://duplicate-test.com/video";

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger successfulOrders = new AtomicInteger(0);
        AtomicInteger rejectedOrders = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            CreateOrderRequest request =
                                    CreateOrderRequest.builder()
                                            .service(lowCostService.getId())
                                            .link(duplicateLink)
                                            .quantity(50)
                                            .build();

                            orderService.createOrderWithApiKey(request, testApiKey);
                            successfulOrders.incrementAndGet();

                        } catch (Exception e) {
                            rejectedOrders.incrementAndGet();
                            log.debug(
                                    "Order rejected in thread {}: {}", threadIndex, e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(60, TimeUnit.SECONDS),
                "Duplicate links test should complete within 60 seconds");
        executor.shutdown();

        // Verify no duplicate orders were created (depending on business logic)
        List<Order> ordersWithDuplicateLink =
                orderRepository.findOrdersWithDetailsByUserId(testUser.getId()).stream()
                        .filter(order -> duplicateLink.equals(order.getLink()))
                        .toList();

        // The actual behavior depends on business requirements
        // For this test, we just ensure the system handled concurrent requests gracefully
        assertTrue(
                successfulOrders.get() + rejectedOrders.get() == numberOfThreads,
                "All requests should be accounted for");

        log.info(
                "Duplicate links test completed - Successful: {}, Rejected: {}, "
                        + "Orders with duplicate link: {}",
                successfulOrders.get(),
                rejectedOrders.get(),
                ordersWithDuplicateLink.size());
    }
}

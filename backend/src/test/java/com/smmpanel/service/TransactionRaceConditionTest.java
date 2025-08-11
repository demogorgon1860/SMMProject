package com.smmpanel.service;

import static org.junit.jupiter.api.Assertions.*;

import com.smmpanel.entity.*;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.repository.jpa.BalanceTransactionRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.repository.jpa.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class TransactionRaceConditionTest {

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

    @Autowired private BalanceService balanceService;

    @Autowired private UserRepository userRepository;

    @Autowired private BalanceTransactionRepository transactionRepository;

    @Autowired private ServiceRepository serviceRepository;

    @Autowired private OrderRepository orderRepository;

    private User testUser;
    private com.smmpanel.entity.Service testService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up previous test data
        transactionRepository.deleteAll();
        orderRepository.deleteAll();
        serviceRepository.deleteAll();
        userRepository.deleteAll();

        // Create test user with initial balance
        testUser =
                User.builder()
                        .username("racetest")
                        .email("race@test.com")
                        .passwordHash("password")
                        .role(UserRole.USER)
                        .balance(new BigDecimal("1000.00"))
                        .totalSpent(BigDecimal.ZERO)
                        .createdAt(LocalDateTime.now())
                        .version(0L)
                        .build();

        testUser = userRepository.save(testUser);

        // Create test service
        testService =
                com.smmpanel.entity.Service.builder()
                        .name("Test Service")
                        .pricePer1000(new BigDecimal("0.01"))
                        .minOrder(1)
                        .maxOrder(10000)
                        .active(true)
                        .description("Test service for race condition tests")
                        .build();

        testService = serviceRepository.save(testService);
    }

    @Test
    void testConcurrentBalanceCheckAndDeduct() throws InterruptedException {
        // Scenario: Multiple threads trying to deduct from the same user simultaneously
        int numberOfThreads = 20;
        BigDecimal deductionAmount =
                new BigDecimal("100.00"); // Total would be 2000, but user only has 1000

        // Extract final variables for lambda usage
        final User finalTestUser = testUser;
        final com.smmpanel.entity.Service finalTestService = testService;

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successfulDeductions = new AtomicInteger(0);
        AtomicInteger failedDeductions = new AtomicInteger(0);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            Order testOrder =
                                    Order.builder()
                                            .user(finalTestUser)
                                            .service(finalTestService)
                                            .link("https://test" + threadIndex + ".com")
                                            .quantity(100)
                                            .charge(deductionAmount)
                                            .status(OrderStatus.PENDING)
                                            .createdAt(LocalDateTime.now())
                                            .build();
                            testOrder = orderRepository.save(testOrder);

                            boolean success =
                                    balanceService.checkAndDeductBalance(
                                            finalTestUser,
                                            deductionAmount,
                                            testOrder,
                                            "Race condition test " + threadIndex);

                            if (success) {
                                successfulDeductions.incrementAndGet();
                            } else {
                                failedDeductions.incrementAndGet();
                            }
                        } catch (Exception e) {
                            exceptions.add(e);
                            failedDeductions.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(30, TimeUnit.SECONDS),
                "Race condition test should complete within 30 seconds");
        executor.shutdown();

        // Verify results
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();

        // Only successful deductions should have been processed
        assertEquals(
                10,
                successfulDeductions.get(),
                "Should have exactly 10 successful deductions (1000/100)");
        assertEquals(
                10,
                failedDeductions.get(),
                "Should have 10 failed deductions due to insufficient balance");

        // Final balance should be zero
        assertEquals(new BigDecimal("0.00"), updatedUser.getBalance());

        // Total spent should equal successful deductions
        BigDecimal expectedTotalSpent =
                deductionAmount.multiply(new BigDecimal(successfulDeductions.get()));
        assertEquals(expectedTotalSpent, updatedUser.getTotalSpent());

        // Verify transaction consistency
        List<BalanceTransaction> transactions =
                transactionRepository.findByUserId(testUser.getId(), null).getContent();
        assertEquals(
                successfulDeductions.get(),
                transactions.size(),
                "Should have one transaction per successful deduction");
    }

    @Test
    void testConcurrentTransfers() throws InterruptedException {
        // Create a second user
        User user2 =
                User.builder()
                        .username("racetest2")
                        .email("race2@test.com")
                        .passwordHash("password")
                        .role(UserRole.USER)
                        .balance(new BigDecimal("500.00"))
                        .totalSpent(BigDecimal.ZERO)
                        .createdAt(LocalDateTime.now())
                        .version(0L)
                        .build();
        user2 = userRepository.save(user2);

        int numberOfThreads = 20;
        BigDecimal transferAmount = new BigDecimal("25.00");

        // Extract final variables for lambda usage
        final Long testUserId = testUser.getId();
        final Long user2Id = user2.getId();

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger failedTransfers = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            if (threadIndex % 2 == 0) {
                                // Transfer from testUser to user2
                                balanceService.transferBalance(
                                        testUserId,
                                        user2Id,
                                        transferAmount,
                                        "Race test transfer " + threadIndex);
                            } else {
                                // Transfer from user2 to testUser
                                balanceService.transferBalance(
                                        user2Id,
                                        testUserId,
                                        transferAmount,
                                        "Race test transfer " + threadIndex);
                            }
                            successfulTransfers.incrementAndGet();
                        } catch (InsufficientBalanceException e) {
                            failedTransfers.incrementAndGet();
                        } catch (Exception e) {
                            failedTransfers.incrementAndGet();
                            System.err.println(
                                    "Unexpected exception in transfer: " + e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(45, TimeUnit.SECONDS),
                "Transfer race condition test should complete within 45 seconds");
        executor.shutdown();

        // Verify total balance is conserved
        User finalUser1 = userRepository.findById(testUser.getId()).orElseThrow();
        User finalUser2 = userRepository.findById(user2.getId()).orElseThrow();

        BigDecimal totalFinalBalance = finalUser1.getBalance().add(finalUser2.getBalance());
        BigDecimal expectedTotalBalance = new BigDecimal("1500.00"); // 1000 + 500

        assertEquals(
                expectedTotalBalance,
                totalFinalBalance,
                "Total balance should be conserved across all transfers");

        assertTrue(
                successfulTransfers.get() + failedTransfers.get() == numberOfThreads,
                "All transfer attempts should be accounted for");
    }

    @Test
    void testDeadlockPrevention() throws InterruptedException {
        // Create multiple users
        List<User> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User user =
                    User.builder()
                            .username("deadlocktest" + i)
                            .email("deadlock" + i + "@test.com")
                            .passwordHash("password")
                            .role(UserRole.USER)
                            .balance(new BigDecimal("200.00"))
                            .totalSpent(BigDecimal.ZERO)
                            .createdAt(LocalDateTime.now())
                            .version(0L)
                            .build();
            users.add(userRepository.save(user));
        }

        int numberOfThreads = 50;
        BigDecimal transferAmount = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger completedTransfers = new AtomicInteger(0);
        AtomicLong maxExecutionTime = new AtomicLong(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        long startTime = System.currentTimeMillis();
                        try {
                            // Create circular transfer pattern to test deadlock prevention
                            User fromUser = users.get(threadIndex % users.size());
                            User toUser = users.get((threadIndex + 1) % users.size());

                            balanceService.transferBalance(
                                    fromUser.getId(),
                                    toUser.getId(),
                                    transferAmount,
                                    "Deadlock prevention test " + threadIndex);

                            completedTransfers.incrementAndGet();
                        } catch (Exception e) {
                            // Expected in some cases due to insufficient balance
                        } finally {
                            long executionTime = System.currentTimeMillis() - startTime;
                            maxExecutionTime.accumulateAndGet(executionTime, Math::max);
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(60, TimeUnit.SECONDS),
                "Deadlock prevention test should complete within 60 seconds");
        executor.shutdown();

        // Verify no deadlocks occurred (all operations completed within reasonable time)
        assertTrue(
                maxExecutionTime.get() < 30000,
                "No single operation should take more than 30 seconds (indicating potential"
                        + " deadlock)");

        // Verify total balance is conserved
        BigDecimal totalBalance =
                users.stream()
                        .map(u -> userRepository.findById(u.getId()).orElseThrow().getBalance())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(
                new BigDecimal("1000.00"),
                totalBalance, // 5 users * 200 each
                "Total balance should be conserved despite concurrent transfers");
    }

    @Test
    void testHighConcurrencyMixedOperations() throws InterruptedException {
        // Create additional users for complex scenarios
        User user2 =
                User.builder()
                        .username("mixedtest")
                        .email("mixed@test.com")
                        .passwordHash("password")
                        .role(UserRole.USER)
                        .balance(new BigDecimal("300.00"))
                        .totalSpent(BigDecimal.ZERO)
                        .createdAt(LocalDateTime.now())
                        .version(0L)
                        .build();
        user2 = userRepository.save(user2);

        int numberOfThreads = 100;

        // Extract final variables for lambda usage
        final Long testUserId = testUser.getId();
        final Long user2Id = user2.getId();
        final User finalTestUser = testUser;
        final com.smmpanel.entity.Service finalTestService = testService;

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger operationCounts = new AtomicInteger(0);
        AtomicInteger exceptions = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            switch (threadIndex % 6) {
                                case 0: // Add balance
                                    balanceService.addBalance(
                                            finalTestUser,
                                            new BigDecimal("5.00"),
                                            null,
                                            "Mixed test add");
                                    break;
                                case 1: // Check and deduct
                                    Order order =
                                            Order.builder()
                                                    .user(finalTestUser)
                                                    .service(finalTestService)
                                                    .link("https://mixed" + threadIndex + ".com")
                                                    .quantity(50)
                                                    .charge(new BigDecimal("10.00"))
                                                    .status(OrderStatus.PENDING)
                                                    .createdAt(LocalDateTime.now())
                                                    .build();
                                    order = orderRepository.save(order);
                                    balanceService.checkAndDeductBalance(
                                            finalTestUser,
                                            new BigDecimal("10.00"),
                                            order,
                                            "Mixed test deduct");
                                    break;
                                case 2: // Transfer
                                    try {
                                        balanceService.transferBalance(
                                                testUserId,
                                                user2Id,
                                                new BigDecimal("15.00"),
                                                "Mixed test transfer");
                                    } catch (InsufficientBalanceException ignored) {
                                    }
                                    break;
                                case 3: // Adjust balance
                                    balanceService.adjustBalance(
                                            testUserId,
                                            new BigDecimal("3.00"),
                                            TransactionType.ADJUSTMENT,
                                            "Mixed test adjustment",
                                            null);
                                    break;
                                case 4: // Check balance
                                    balanceService.checkAndReserveBalance(
                                            testUserId, new BigDecimal("20.00"));
                                    break;
                                case 5: // Refund
                                    balanceService.refund(
                                            finalTestUser,
                                            new BigDecimal("2.00"),
                                            null,
                                            "Mixed test refund");
                                    break;
                            }
                            operationCounts.incrementAndGet();
                        } catch (Exception e) {
                            exceptions.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(120, TimeUnit.SECONDS),
                "Mixed operations test should complete within 2 minutes");
        executor.shutdown();

        // Verify system remained consistent
        User finalUser1 = userRepository.findById(testUser.getId()).orElseThrow();
        User finalUser2 = userRepository.findById(user2.getId()).orElseThrow();

        assertNotNull(finalUser1.getBalance());
        assertNotNull(finalUser2.getBalance());
        assertTrue(
                finalUser1.getBalance().compareTo(BigDecimal.ZERO) >= 0,
                "User balance should never go negative");
        assertTrue(
                finalUser2.getBalance().compareTo(BigDecimal.ZERO) >= 0,
                "User balance should never go negative");

        // Verify transaction records exist and are consistent
        List<BalanceTransaction> allTransactions = transactionRepository.findAll();
        assertFalse(allTransactions.isEmpty(), "Should have transaction records");

        // Check transaction integrity
        for (BalanceTransaction transaction : allTransactions) {
            assertNotNull(transaction.getBalanceBefore());
            assertNotNull(transaction.getBalanceAfter());
            assertNotNull(transaction.getAmount());
            assertNotNull(transaction.getTransactionType());
            assertNotNull(transaction.getCreatedAt());
        }

        System.out.printf(
                "Mixed operations test completed: %d successful operations, %d exceptions%n",
                operationCounts.get(), exceptions.get());
    }

    @Test
    void testTransactionTimeouts() throws InterruptedException {
        // This test simulates scenarios where transactions might timeout
        int numberOfThreads = 30;

        // Extract final variable for lambda usage
        final Long testUserId = testUser.getId();

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger completedOperations = new AtomicInteger(0);
        AtomicInteger timeoutOperations = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            // Perform a complex operation that might stress the transaction system
                            for (int j = 0; j < 10; j++) {
                                try {
                                    balanceService.adjustBalance(
                                            testUserId,
                                            new BigDecimal("0.01"),
                                            TransactionType.ADJUSTMENT,
                                            "Timeout test " + threadIndex + "-" + j,
                                            null);
                                    Thread.sleep(10); // Small delay to increase contention
                                } catch (Exception e) {
                                    if (e.getMessage().contains("timeout")
                                            || e.getMessage().contains("deadlock")) {
                                        timeoutOperations.incrementAndGet();
                                    }
                                    break;
                                }
                            }
                            completedOperations.incrementAndGet();
                        } catch (Exception e) {
                            // Expected in high contention scenarios
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(90, TimeUnit.SECONDS),
                "Timeout test should complete within 90 seconds");
        executor.shutdown();

        // Verify system handled timeouts gracefully
        User finalUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertNotNull(finalUser.getBalance());
        assertTrue(finalUser.getBalance().compareTo(BigDecimal.ZERO) >= 0);

        System.out.printf(
                "Timeout test completed: %d operations completed, %d timeouts detected%n",
                completedOperations.get(), timeoutOperations.get());
    }
}

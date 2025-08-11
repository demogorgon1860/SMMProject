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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Slf4j
@SpringBootTest
@Testcontainers
class BalanceConcurrencyStressTest {

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
        registry.add("app.balance.retry.initial-delay", () -> "25");
        registry.add("app.balance.retry.max-delay", () -> "500");
        registry.add("app.balance.retry.multiplier", () -> "2.0");
        registry.add("app.transaction.monitoring.enabled", () -> "true");
        registry.add("spring.jpa.properties.hibernate.connection.pool_size", () -> "50");
    }

    @Autowired private BalanceService balanceService;

    @Autowired private UserRepository userRepository;

    @Autowired private BalanceTransactionRepository transactionRepository;

    @Autowired private ServiceRepository serviceRepository;

    @Autowired private OrderRepository orderRepository;

    private User testUser;
    private List<User> multipleUsers;
    private com.smmpanel.entity.Service testService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up previous test data
        transactionRepository.deleteAll();
        orderRepository.deleteAll();
        serviceRepository.deleteAll();
        userRepository.deleteAll();

        // Create main test user
        testUser = createUser("stresstest", "stress@test.com", new BigDecimal("10000.00"));

        // Create multiple test users for complex scenarios
        multipleUsers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            multipleUsers.add(
                    createUser("user" + i, "user" + i + "@test.com", new BigDecimal("1000.00")));
        }

        // Create test service
        testService =
                com.smmpanel.entity.Service.builder()
                        .name("Stress Test Service")
                        .pricePer1000(new BigDecimal("0.01"))
                        .minOrder(1)
                        .maxOrder(100000)
                        .active(true)
                        .description("Service for stress testing")
                        .build();
        testService = serviceRepository.save(testService);
    }

    private User createUser(String username, String email, BigDecimal balance) {
        User user =
                User.builder()
                        .username(username)
                        .email(email)
                        .passwordHash("password")
                        .role(UserRole.USER)
                        .balance(balance)
                        .totalSpent(BigDecimal.ZERO)
                        .createdAt(LocalDateTime.now())
                        .version(0L)
                        .build();
        return userRepository.save(user);
    }

    @Test
    @Timeout(120)
    void testHighVolumeBalanceUpdates() throws InterruptedException {
        log.info("Starting high volume balance updates test");

        int numberOfThreads = 50;
        int operationsPerThread = 20;
        BigDecimal operationAmount = new BigDecimal("1.00");

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger credits = new AtomicInteger(0);
        AtomicInteger debits = new AtomicInteger(0);
        AtomicInteger optimisticLockFailures = new AtomicInteger(0);
        AtomicLong totalExecutionTime = new AtomicLong(0);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        long threadStartTime = System.currentTimeMillis();
                        try {
                            for (int j = 0; j < operationsPerThread; j++) {
                                try {
                                    if ((threadIndex + j) % 2 == 0) {
                                        // Credit operation
                                        balanceService.addBalance(
                                                testUser,
                                                operationAmount,
                                                null,
                                                "Stress test credit " + threadIndex + "-" + j);
                                        credits.incrementAndGet();
                                    } else {
                                        // Debit operation
                                        Order order = createTestOrder(testUser, operationAmount);
                                        boolean success =
                                                balanceService.checkAndDeductBalance(
                                                        testUser,
                                                        operationAmount,
                                                        order,
                                                        "Stress test debit "
                                                                + threadIndex
                                                                + "-"
                                                                + j);
                                        if (success) {
                                            debits.incrementAndGet();
                                        }
                                    }

                                    // Add small random delay to increase contention
                                    if (ThreadLocalRandom.current().nextInt(100) < 10) {
                                        Thread.sleep(ThreadLocalRandom.current().nextInt(5));
                                    }
                                } catch (ObjectOptimisticLockingFailureException e) {
                                    optimisticLockFailures.incrementAndGet();
                                } catch (Exception e) {
                                    exceptions.add(e);
                                }
                            }
                        } finally {
                            totalExecutionTime.addAndGet(
                                    System.currentTimeMillis() - threadStartTime);
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(120, TimeUnit.SECONDS),
                "High volume test should complete within 2 minutes");
        executor.shutdown();

        // Verify results
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        BigDecimal expectedBalance =
                new BigDecimal("10000.00")
                        .add(operationAmount.multiply(new BigDecimal(credits.get())))
                        .subtract(operationAmount.multiply(new BigDecimal(debits.get())));

        assertEquals(
                expectedBalance,
                updatedUser.getBalance(),
                "Final balance should equal initial balance plus credits minus debits");

        // Verify transaction consistency
        List<BalanceTransaction> transactions =
                transactionRepository.findByUserId(testUser.getId(), null).getContent();
        assertEquals(
                credits.get() + debits.get(),
                transactions.size(),
                "Should have one transaction per successful operation");

        log.info(
                "High volume test completed - Credits: {}, Debits: {}, Optimistic lock failures:"
                        + " {}, Average thread time: {}ms, Total exceptions: {}",
                credits.get(),
                debits.get(),
                optimisticLockFailures.get(),
                totalExecutionTime.get() / numberOfThreads,
                exceptions.size());

        // Ensure we didn't have too many failures
        assertTrue(
                exceptions.size() < numberOfThreads * operationsPerThread * 0.1,
                "Exception rate should be less than 10%");
    }

    @Test
    @Timeout(90)
    void testConcurrentBalanceTransfers() throws InterruptedException {
        log.info("Starting concurrent balance transfers test");

        int numberOfThreads = 30;
        int transfersPerThread = 10;
        BigDecimal transferAmount = new BigDecimal("5.00");

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger failedTransfers = new AtomicInteger(0);
        AtomicReference<BigDecimal> totalInitialBalance = new AtomicReference<>(BigDecimal.ZERO);

        // Calculate total initial balance
        multipleUsers.forEach(
                user ->
                        totalInitialBalance.updateAndGet(
                                balance -> balance.add(user.getBalance())));

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < transfersPerThread; j++) {
                                try {
                                    // Random transfer between users
                                    User fromUser =
                                            multipleUsers.get(
                                                    ThreadLocalRandom.current()
                                                            .nextInt(multipleUsers.size()));
                                    User toUser;
                                    do {
                                        toUser =
                                                multipleUsers.get(
                                                        ThreadLocalRandom.current()
                                                                .nextInt(multipleUsers.size()));
                                    } while (toUser.getId().equals(fromUser.getId()));

                                    balanceService.transferBalance(
                                            fromUser.getId(),
                                            toUser.getId(),
                                            transferAmount,
                                            "Concurrent transfer test " + threadIndex + "-" + j);
                                    successfulTransfers.incrementAndGet();

                                } catch (InsufficientBalanceException e) {
                                    failedTransfers.incrementAndGet();
                                } catch (Exception e) {
                                    failedTransfers.incrementAndGet();
                                    log.warn("Unexpected transfer exception: {}", e.getMessage());
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(90, TimeUnit.SECONDS),
                "Transfer test should complete within 90 seconds");
        executor.shutdown();

        // Verify balance conservation
        BigDecimal totalFinalBalance = BigDecimal.ZERO;
        for (User user : multipleUsers) {
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            totalFinalBalance = totalFinalBalance.add(updatedUser.getBalance());
        }

        assertEquals(
                totalInitialBalance.get(),
                totalFinalBalance,
                "Total balance across all users should be conserved");

        log.info(
                "Transfer test completed - Successful: {}, Failed: {}",
                successfulTransfers.get(),
                failedTransfers.get());
    }

    @Test
    @Timeout(180)
    void testMixedOperationsUnderHighLoad() throws InterruptedException {
        log.info("Starting mixed operations under high load test");

        int numberOfThreads = 40;
        int operationsPerThread = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger[] operationCounts = new AtomicInteger[6];
        for (int i = 0; i < operationCounts.length; i++) {
            operationCounts[i] = new AtomicInteger(0);
        }

        AtomicInteger totalExceptions = new AtomicInteger(0);
        AtomicLong maxOperationTime = new AtomicLong(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < operationsPerThread; j++) {
                                long operationStart = System.currentTimeMillis();
                                try {
                                    User targetUser =
                                            multipleUsers.get(
                                                    ThreadLocalRandom.current()
                                                            .nextInt(multipleUsers.size()));

                                    switch ((threadIndex + j) % 6) {
                                        case 0: // Add balance
                                            balanceService.addBalance(
                                                    targetUser,
                                                    new BigDecimal("2.50"),
                                                    null,
                                                    "Mixed load test add");
                                            operationCounts[0].incrementAndGet();
                                            break;

                                        case 1: // Check and deduct
                                            Order order =
                                                    createTestOrder(
                                                            targetUser, new BigDecimal("1.75"));
                                            boolean deducted =
                                                    balanceService.checkAndDeductBalance(
                                                            targetUser,
                                                            new BigDecimal("1.75"),
                                                            order,
                                                            "Mixed load test deduct");
                                            if (deducted) operationCounts[1].incrementAndGet();
                                            break;

                                        case 2: // Transfer
                                            User fromUser =
                                                    multipleUsers.get(
                                                            ThreadLocalRandom.current()
                                                                    .nextInt(multipleUsers.size()));
                                            User toUser;
                                            do {
                                                toUser =
                                                        multipleUsers.get(
                                                                ThreadLocalRandom.current()
                                                                        .nextInt(
                                                                                multipleUsers
                                                                                        .size()));
                                            } while (toUser.getId().equals(fromUser.getId()));

                                            try {
                                                balanceService.transferBalance(
                                                        fromUser.getId(),
                                                        toUser.getId(),
                                                        new BigDecimal("3.00"),
                                                        "Mixed load test transfer");
                                                operationCounts[2].incrementAndGet();
                                            } catch (InsufficientBalanceException ignored) {
                                            }
                                            break;

                                        case 3: // Adjust balance
                                            BigDecimal adjustment =
                                                    ThreadLocalRandom.current().nextBoolean()
                                                            ? new BigDecimal("1.25")
                                                            : new BigDecimal("-0.75");
                                            try {
                                                balanceService.adjustBalance(
                                                        targetUser.getId(),
                                                        adjustment,
                                                        TransactionType.ADJUSTMENT,
                                                        "Mixed load test adjust",
                                                        null);
                                                operationCounts[3].incrementAndGet();
                                            } catch (InsufficientBalanceException ignored) {
                                            }
                                            break;

                                        case 4: // Check balance
                                            balanceService.checkAndReserveBalance(
                                                    targetUser.getId(), new BigDecimal("5.00"));
                                            operationCounts[4].incrementAndGet();
                                            break;

                                        case 5: // Refund
                                            balanceService.refund(
                                                    targetUser,
                                                    new BigDecimal("1.00"),
                                                    null,
                                                    "Mixed load test refund");
                                            operationCounts[5].incrementAndGet();
                                            break;
                                    }
                                } catch (Exception e) {
                                    totalExceptions.incrementAndGet();
                                } finally {
                                    long operationTime =
                                            System.currentTimeMillis() - operationStart;
                                    maxOperationTime.accumulateAndGet(operationTime, Math::max);
                                }
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(180, TimeUnit.SECONDS),
                "Mixed operations test should complete within 3 minutes");
        executor.shutdown();

        // Verify all users have valid balances
        for (User user : multipleUsers) {
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            assertTrue(
                    updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0,
                    "User balance should never go negative: " + updatedUser.getUsername());
        }

        log.info(
                "Mixed operations test completed - Operation counts: [Add: {}, Deduct: {},"
                        + " Transfer: {}, Adjust: {}, Check: {}, Refund: {}], Exceptions: {}, Max"
                        + " operation time: {}ms",
                operationCounts[0].get(),
                operationCounts[1].get(),
                operationCounts[2].get(),
                operationCounts[3].get(),
                operationCounts[4].get(),
                operationCounts[5].get(),
                totalExceptions.get(),
                maxOperationTime.get());
    }

    @Test
    @Timeout(60)
    void testBalanceUpdatesPrecisionUnderConcurrency() throws InterruptedException {
        log.info("Starting precision test under concurrency");

        int numberOfThreads = 20;
        BigDecimal preciseAmount = new BigDecimal("0.12345678"); // 8 decimal places

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger operations = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < 10; j++) {
                                if (j % 2 == 0) {
                                    balanceService.addBalance(
                                            testUser,
                                            preciseAmount,
                                            null,
                                            "Precision test add " + threadIndex + "-" + j);
                                } else {
                                    try {
                                        Order order = createTestOrder(testUser, preciseAmount);
                                        balanceService.checkAndDeductBalance(
                                                testUser,
                                                preciseAmount,
                                                order,
                                                "Precision test deduct " + threadIndex + "-" + j);
                                    } catch (InsufficientBalanceException ignored) {
                                    }
                                }
                                operations.incrementAndGet();
                            }
                        } catch (Exception e) {
                            log.error(
                                    "Precision test error in thread {}: {}",
                                    threadIndex,
                                    e.getMessage());
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(60, TimeUnit.SECONDS),
                "Precision test should complete within 60 seconds");
        executor.shutdown();

        // Verify precision is maintained
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertEquals(
                8,
                updatedUser.getBalance().scale(),
                "Balance precision should be maintained at 8 decimal places");

        // Verify all transactions have correct precision
        List<BalanceTransaction> transactions =
                transactionRepository.findByUserId(testUser.getId(), null).getContent();
        for (BalanceTransaction transaction : transactions) {
            assertTrue(
                    transaction.getAmount().scale() <= 8,
                    "All transaction amounts should have proper scale");
            assertTrue(
                    transaction.getBalanceBefore().scale() <= 8,
                    "Balance before should have proper scale");
            assertTrue(
                    transaction.getBalanceAfter().scale() <= 8,
                    "Balance after should have proper scale");
        }

        log.info(
                "Precision test completed - {} operations, final balance: {}",
                operations.get(),
                updatedUser.getBalance());
    }

    @Test
    @Timeout(120)
    void testConcurrentBalanceZeroingPrevention() throws InterruptedException {
        log.info("Starting balance zeroing prevention test");

        // Create user with limited balance
        User limitedUser = createUser("limited", "limited@test.com", new BigDecimal("100.00"));

        int numberOfThreads = 50;
        BigDecimal attemptAmount =
                new BigDecimal("10.00"); // Each thread tries to deduct 10, total would be 500

        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        AtomicInteger successfulDeductions = new AtomicInteger(0);
        AtomicInteger preventedDeductions = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(
                    () -> {
                        try {
                            Order order = createTestOrder(limitedUser, attemptAmount);
                            boolean success =
                                    balanceService.checkAndDeductBalance(
                                            limitedUser,
                                            attemptAmount,
                                            order,
                                            "Zeroing prevention test " + threadIndex);

                            if (success) {
                                successfulDeductions.incrementAndGet();
                            } else {
                                preventedDeductions.incrementAndGet();
                            }
                        } catch (Exception e) {
                            preventedDeductions.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        assertTrue(
                latch.await(120, TimeUnit.SECONDS),
                "Zeroing prevention test should complete within 2 minutes");
        executor.shutdown();

        // Verify results
        User updatedUser = userRepository.findById(limitedUser.getId()).orElseThrow();

        // Should have exactly 10 successful deductions (100/10)
        assertEquals(
                10, successfulDeductions.get(), "Should have exactly 10 successful deductions");
        assertEquals(40, preventedDeductions.get(), "Should have 40 prevented deductions");
        assertEquals(BigDecimal.ZERO, updatedUser.getBalance(), "Final balance should be zero");

        // Verify no negative balance ever occurred
        assertTrue(
                updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0,
                "Balance should never go negative");

        log.info(
                "Zeroing prevention test completed - Successful: {}, Prevented: {}, Final balance:"
                        + " {}",
                successfulDeductions.get(),
                preventedDeductions.get(),
                updatedUser.getBalance());
    }

    private Order createTestOrder(User user, BigDecimal amount) {
        Order order =
                Order.builder()
                        .user(user)
                        .service(testService)
                        .link("https://test.com/" + System.currentTimeMillis())
                        .quantity(100)
                        .charge(amount)
                        .status(OrderStatus.PENDING)
                        .createdAt(LocalDateTime.now())
                        .build();
        return orderRepository.save(order);
    }
}

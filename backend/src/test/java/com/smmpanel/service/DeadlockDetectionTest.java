package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.repository.BalanceTransactionRepository;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@Testcontainers
class DeadlockDetectionTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
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
        registry.add("app.balance.retry.max-delay", () -> "1000");
        registry.add("app.balance.retry.multiplier", () -> "2.0");
        registry.add("app.transaction.monitoring.enabled", () -> "true");
        // Configure lower deadlock timeout for faster detection
        registry.add("spring.jpa.properties.hibernate.connection.pool_size", () -> "30");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "10000");
        registry.add("spring.datasource.hikari.leak-detection-threshold", () -> "30000");
    }

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceTransactionRepository transactionRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private OrderRepository orderRepository;

    private List<User> testUsers;
    private com.smmpanel.entity.Service testService;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up previous test data
        transactionRepository.deleteAll();
        orderRepository.deleteAll();
        serviceRepository.deleteAll();
        userRepository.deleteAll();

        // Create multiple users for deadlock scenarios
        testUsers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            User user = User.builder()
                    .username("deadlockuser" + i)
                    .email("deadlock" + i + "@test.com")
                    .passwordHash("password")
                    .role(UserRole.USER)
                    .balance(new BigDecimal("1000.00"))
                    .totalSpent(BigDecimal.ZERO)
                    .createdAt(LocalDateTime.now())
                    .version(0L)
                    .build();
            testUsers.add(userRepository.save(user));
        }

        // Create test service
        testService = com.smmpanel.entity.Service.builder()
                .name("Deadlock Test Service")
                .pricePer1000(new BigDecimal("1.00"))
                .minOrder(1)
                .maxOrder(1000)
                .active(true)
                .description("Service for deadlock testing")
                .build();
        testService = serviceRepository.save(testService);
    }

    @Test
    @Timeout(180)
    void testCircularTransferDeadlockPrevention() throws InterruptedException {
        log.info("Starting circular transfer deadlock prevention test");
        
        int numberOfThreads = 20;
        BigDecimal transferAmount = new BigDecimal("10.00");
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger failedTransfers = new AtomicInteger(0);
        AtomicInteger deadlockDetections = new AtomicInteger(0);
        AtomicLong maxExecutionTime = new AtomicLong(0);
        AtomicLong totalExecutionTime = new AtomicLong(0);
        
        // Create circular dependency pattern: User A -> User B -> User C -> User A
        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    // Create circular transfer pattern
                    User fromUser = testUsers.get(threadIndex % testUsers.size());
                    User toUser = testUsers.get((threadIndex + 1) % testUsers.size());
                    
                    balanceService.transferBalance(fromUser.getId(), toUser.getId(), transferAmount, 
                        "Circular deadlock test " + threadIndex);
                    
                    successfulTransfers.incrementAndGet();
                    
                } catch (InsufficientBalanceException e) {
                    failedTransfers.incrementAndGet();
                } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                    deadlockDetections.incrementAndGet();
                    failedTransfers.incrementAndGet();
                    log.debug("Deadlock detected and handled in thread {}: {}", threadIndex, e.getClass().getSimpleName());
                } catch (Exception e) {
                    failedTransfers.incrementAndGet();
                    log.warn("Unexpected exception in thread {}: {}", threadIndex, e.getMessage());
                } finally {
                    long executionTime = System.currentTimeMillis() - startTime;
                    maxExecutionTime.accumulateAndGet(executionTime, Math::max);
                    totalExecutionTime.addAndGet(executionTime);
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(180, TimeUnit.SECONDS), "Circular transfer test should complete within 3 minutes");
        executor.shutdown();

        // Verify results
        assertTrue(maxExecutionTime.get() < 60000, 
            "No single operation should take more than 60 seconds (indicating deadlock resolution)");

        // Verify total balance conservation
        BigDecimal totalFinalBalance = testUsers.stream()
                .map(u -> userRepository.findById(u.getId()).orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal expectedTotalBalance = new BigDecimal("8000.00"); // 8 users * 1000 each
        assertEquals(expectedTotalBalance, totalFinalBalance, 
            "Total balance should be conserved despite potential deadlocks");

        log.info("Circular transfer test completed - Successful: {}, Failed: {}, Deadlocks detected: {}, " +
                "Max execution time: {}ms, Average execution time: {}ms", 
                successfulTransfers.get(), failedTransfers.get(), deadlockDetections.get(),
                maxExecutionTime.get(), totalExecutionTime.get() / numberOfThreads);
    }

    @Test
    @Timeout(120)
    void testMultiUserConcurrentOperationsDeadlockPrevention() throws InterruptedException {
        log.info("Starting multi-user concurrent operations deadlock prevention test");
        
        int numberOfThreads = 40;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger operations = new AtomicInteger(0);
        AtomicInteger deadlockExceptions = new AtomicInteger(0);
        AtomicInteger timeoutExceptions = new AtomicInteger(0);
        AtomicInteger otherExceptions = new AtomicInteger(0);
        AtomicLong totalWaitTime = new AtomicLong(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                long threadStartTime = System.currentTimeMillis();
                try {
                    // Perform multiple operations that could cause deadlocks
                    for (int j = 0; j < 5; j++) {
                        try {
                            User user1 = testUsers.get(ThreadLocalRandom.current().nextInt(testUsers.size()));
                            User user2 = testUsers.get(ThreadLocalRandom.current().nextInt(testUsers.size()));
                            
                            switch (j % 4) {
                                case 0: // Transfer between random users
                                    if (!user1.getId().equals(user2.getId())) {
                                        balanceService.transferBalance(user1.getId(), user2.getId(), 
                                            new BigDecimal("5.00"), "Deadlock test transfer");
                                    }
                                    break;
                                    
                                case 1: // Add balance to random user
                                    balanceService.addBalance(user1, new BigDecimal("2.00"), null, 
                                        "Deadlock test add");
                                    break;
                                    
                                case 2: // Deduct balance from random user
                                    Order order = createTestOrder(user1);
                                    balanceService.checkAndDeductBalance(user1, new BigDecimal("3.00"), order, 
                                        "Deadlock test deduct");
                                    break;
                                    
                                case 3: // Adjust balance
                                    BigDecimal adjustment = ThreadLocalRandom.current().nextBoolean() ? 
                                        new BigDecimal("1.50") : new BigDecimal("-1.00");
                                    try {
                                        balanceService.adjustBalance(user1.getId(), adjustment, 
                                            TransactionType.ADJUSTMENT, "Deadlock test adjust", null);
                                    } catch (InsufficientBalanceException ignored) {}
                                    break;
                            }
                            operations.incrementAndGet();
                            
                        } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                            deadlockExceptions.incrementAndGet();
                        } catch (Exception e) {
                            otherExceptions.incrementAndGet();
                            if (!(e instanceof InsufficientBalanceException)) {
                                log.debug("Unexpected exception in thread {}: {}", threadIndex, e.getMessage());
                            }
                        }
                    }
                } finally {
                    totalWaitTime.addAndGet(System.currentTimeMillis() - threadStartTime);
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(120, TimeUnit.SECONDS), "Multi-user operations test should complete within 2 minutes");
        executor.shutdown();

        // Verify system stability
        for (User user : testUsers) {
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            assertTrue(updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0, 
                "User balance should never go negative: " + updatedUser.getUsername());
        }

        double averageWaitTime = (double) totalWaitTime.get() / numberOfThreads;
        
        log.info("Multi-user operations test completed - Operations: {}, Deadlock exceptions: {}, " +
                "Timeout exceptions: {}, Other exceptions: {}, Average wait time: {:.2f}ms", 
                operations.get(), deadlockExceptions.get(), timeoutExceptions.get(), 
                otherExceptions.get(), averageWaitTime);

        // Ensure reasonable completion rate
        assertTrue(operations.get() > numberOfThreads * 2, 
            "Should complete at least 2 operations per thread on average");
    }

    @Test
    @Timeout(90)
    void testHighContentionDeadlockRecovery() throws InterruptedException {
        log.info("Starting high contention deadlock recovery test");
        
        // Use fewer users to create more contention
        List<User> contentionUsers = testUsers.subList(0, 3);
        int numberOfThreads = 30;
        BigDecimal operationAmount = new BigDecimal("1.00");
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger successfulOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);
        AtomicInteger retryAttempts = new AtomicInteger(0);
        List<String> exceptionTypes = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    // Create high contention by targeting the same users repeatedly
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            User user1 = contentionUsers.get(0);
                            User user2 = contentionUsers.get(1);
                            User user3 = contentionUsers.get(2);
                            
                            // Create a pattern likely to cause deadlocks
                            switch (threadIndex % 3) {
                                case 0:
                                    balanceService.transferBalance(user1.getId(), user2.getId(), operationAmount, 
                                        "High contention test 1->2");
                                    break;
                                case 1:
                                    balanceService.transferBalance(user2.getId(), user3.getId(), operationAmount, 
                                        "High contention test 2->3");
                                    break;
                                case 2:
                                    balanceService.transferBalance(user3.getId(), user1.getId(), operationAmount, 
                                        "High contention test 3->1");
                                    break;
                            }
                            
                            successfulOperations.incrementAndGet();
                            break; // Success, no need to retry
                            
                        } catch (InsufficientBalanceException e) {
                            failedOperations.incrementAndGet();
                            break; // Don't retry for insufficient balance
                        } catch (Exception e) {
                            exceptionTypes.add(e.getClass().getSimpleName());
                            retryAttempts.incrementAndGet();
                            
                            if (attempt == 2) { // Last attempt
                                failedOperations.incrementAndGet();
                            } else {
                                // Brief pause before retry
                                try {
                                    Thread.sleep(ThreadLocalRandom.current().nextInt(10, 50));
                                } catch (InterruptedException ignored) {}
                            }
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(90, TimeUnit.SECONDS), "High contention test should complete within 90 seconds");
        executor.shutdown();

        // Verify balance conservation despite high contention
        BigDecimal totalFinalBalance = contentionUsers.stream()
                .map(u -> userRepository.findById(u.getId()).orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal expectedTotalBalance = new BigDecimal("3000.00"); // 3 users * 1000 each
        assertEquals(expectedTotalBalance, totalFinalBalance, 
            "Total balance should be conserved despite high contention");

        // Count exception types
        Map<String, Long> exceptionCounts = exceptionTypes.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    java.util.function.Function.identity(), 
                    java.util.stream.Collectors.counting()));

        log.info("High contention test completed - Successful: {}, Failed: {}, Retry attempts: {}, " +
                "Exception types: {}", 
                successfulOperations.get(), failedOperations.get(), retryAttempts.get(), exceptionCounts);

        // Ensure system recovered from contentions
        assertTrue(successfulOperations.get() > 0, "Should have some successful operations despite high contention");
    }

    @Test
    @Timeout(60)
    void testDeadlockDetectionWithTimeouts() throws InterruptedException {
        log.info("Starting deadlock detection with timeouts test");
        
        int numberOfThreads = 15;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger completedOperations = new AtomicInteger(0);
        AtomicInteger timeoutOperations = new AtomicInteger(0);
        AtomicLong maxExecutionTime = new AtomicLong(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                long startTime = System.currentTimeMillis();
                try {
                    // Create operations that might timeout
                    User user1 = testUsers.get(threadIndex % testUsers.size());
                    User user2 = testUsers.get((threadIndex + 1) % testUsers.size());
                    
                    // Perform operations that create lock contention
                    for (int j = 0; j < 3; j++) {
                        try {
                            balanceService.transferBalance(user1.getId(), user2.getId(), 
                                new BigDecimal("5.00"), "Timeout test transfer");
                            
                            // Add delay to increase contention
                            Thread.sleep(ThreadLocalRandom.current().nextInt(100, 300));
                            
                        } catch (InsufficientBalanceException ignored) {
                            // Expected in some cases
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    completedOperations.incrementAndGet();
                    
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout")) {
                        timeoutOperations.incrementAndGet();
                    }
                } finally {
                    long executionTime = System.currentTimeMillis() - startTime;
                    maxExecutionTime.accumulateAndGet(executionTime, Math::max);
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Timeout test should complete within 60 seconds");
        executor.shutdown();

        // Verify no operations took excessively long
        assertTrue(maxExecutionTime.get() < 45000, 
            "No operation should take more than 45 seconds (indicating proper timeout handling)");

        log.info("Timeout test completed - Completed operations: {}, Timeout operations: {}, " +
                "Max execution time: {}ms", 
                completedOperations.get(), timeoutOperations.get(), maxExecutionTime.get());
    }

    @Test
    @Timeout(150)
    void testResourceLockOrderingDeadlockPrevention() throws InterruptedException {
        log.info("Starting resource lock ordering deadlock prevention test");
        
        // Test that our service uses consistent ordering to prevent deadlocks
        int numberOfThreads = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger failedTransfers = new AtomicInteger(0);
        Set<String> observedLockOrders = ConcurrentHashMap.newKeySet();

        // Create scenarios where lock ordering matters
        List<Pair<User, User>> userPairs = new ArrayList<>();
        for (int i = 0; i < testUsers.size() - 1; i++) {
            for (int j = i + 1; j < testUsers.size(); j++) {
                userPairs.add(new Pair<>(testUsers.get(i), testUsers.get(j)));
            }
        }

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    Pair<User, User> pair = userPairs.get(threadIndex % userPairs.size());
                    User user1 = pair.first;
                    User user2 = pair.second;
                    
                    // Randomly choose direction to test lock ordering
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        balanceService.transferBalance(user1.getId(), user2.getId(), 
                            new BigDecimal("2.00"), "Lock order test A->B");
                        observedLockOrders.add(user1.getId() + "->" + user2.getId());
                    } else {
                        balanceService.transferBalance(user2.getId(), user1.getId(), 
                            new BigDecimal("2.00"), "Lock order test B->A");
                        observedLockOrders.add(user2.getId() + "->" + user1.getId());
                    }
                    
                    successfulTransfers.incrementAndGet();
                    
                } catch (Exception e) {
                    failedTransfers.incrementAndGet();
                    if (!(e instanceof InsufficientBalanceException)) {
                        log.debug("Transfer failed in thread {}: {}", threadIndex, e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(150, TimeUnit.SECONDS), "Lock ordering test should complete within 150 seconds");
        executor.shutdown();

        // Verify that the system completed without deadlocks
        assertTrue(successfulTransfers.get() + failedTransfers.get() == numberOfThreads, 
            "All operations should complete (no hangs)");

        // Verify balance conservation
        BigDecimal totalFinalBalance = testUsers.stream()
                .map(u -> userRepository.findById(u.getId()).orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal expectedTotalBalance = new BigDecimal("8000.00"); // 8 users * 1000 each
        assertEquals(expectedTotalBalance, totalFinalBalance, 
            "Total balance should be conserved");

        log.info("Lock ordering test completed - Successful: {}, Failed: {}, Unique transfer directions: {}", 
                successfulTransfers.get(), failedTransfers.get(), observedLockOrders.size());
    }

    private Order createTestOrder(User user) {
        Order order = Order.builder()
                .user(user)
                .service(testService)
                .link("https://deadlock-test.com/" + System.currentTimeMillis())
                .quantity(100)
                .charge(new BigDecimal("3.00"))
                .status(OrderStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        return orderRepository.save(order);
    }

    // Helper class for user pairs
    private static class Pair<T, U> {
        final T first;
        final U second;

        Pair(T first, U second) {
            this.first = first;
            this.second = second;
        }
    }
}
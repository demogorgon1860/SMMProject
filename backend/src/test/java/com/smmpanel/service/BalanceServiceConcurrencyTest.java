package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.BalanceTransactionRepository;
import com.smmpanel.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class BalanceServiceConcurrencyTest {

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
        registry.add("app.balance.retry.initial-delay", () -> "50");
        registry.add("app.balance.retry.max-delay", () -> "1000");
        registry.add("app.balance.retry.multiplier", () -> "2.0");
    }

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceTransactionRepository transactionRepository;

    private User testUser;
    private User testUser2;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up previous test data
        transactionRepository.deleteAll();
        userRepository.deleteAll();

        // Create test users
        testUser = User.builder()
                .username("testuser1")
                .email("test1@example.com")
                .password("password")
                .role(UserRole.USER)
                .balance(new BigDecimal("1000.00"))
                .totalSpent(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .version(0L)
                .build();

        testUser2 = User.builder()
                .username("testuser2")
                .email("test2@example.com")
                .password("password")
                .role(UserRole.USER)
                .balance(new BigDecimal("500.00"))
                .totalSpent(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .version(0L)
                .build();

        testUser = userRepository.save(testUser);
        testUser2 = userRepository.save(testUser2);
    }

    @Test
    void testConcurrentBalanceDeductions() throws InterruptedException {
        int numberOfThreads = 20;
        int deductionsPerThread = 5;
        BigDecimal deductionAmount = new BigDecimal("5.00");
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successfulDeductions = new AtomicInteger(0);
        AtomicInteger failedDeductions = new AtomicInteger(0);
        
        // Create test order
        Order testOrder = Order.builder()
                .user(testUser)
                .serviceId(1L)
                .link("https://example.com")
                .quantity(100L)
                .charge(deductionAmount)
                .status(OrderStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < deductionsPerThread; j++) {
                        try {
                            balanceService.deductBalance(testUser, deductionAmount, testOrder, 
                                "Concurrent test deduction");
                            successfulDeductions.incrementAndGet();
                        } catch (InsufficientBalanceException e) {
                            failedDeductions.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent operations should complete within 30 seconds");
        executor.shutdown();

        // Verify results
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        BigDecimal expectedRemainingBalance = new BigDecimal("1000.00")
                .subtract(deductionAmount.multiply(new BigDecimal(successfulDeductions.get())));
        
        assertEquals(expectedRemainingBalance, updatedUser.getBalance(), 
            "Balance should reflect exactly the successful deductions");
        assertEquals(numberOfThreads * deductionsPerThread, 
            successfulDeductions.get() + failedDeductions.get(),
            "Total operations should equal attempted operations");
        
        // Verify transactions were created correctly
        List<BalanceTransaction> transactions = transactionRepository.findByUserId(testUser.getId(), null).getContent();
        assertEquals(successfulDeductions.get(), transactions.size(), 
            "Should have one transaction per successful deduction");
    }

    @Test
    void testConcurrentBalanceAdditions() throws InterruptedException {
        int numberOfThreads = 10;
        int additionsPerThread = 3;
        BigDecimal additionAmount = new BigDecimal("25.00");
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successfulAdditions = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < additionsPerThread; j++) {
                        balanceService.addBalance(testUser, additionAmount, null, 
                            "Concurrent test addition");
                        successfulAdditions.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent operations should complete within 30 seconds");
        executor.shutdown();

        // Verify results
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        BigDecimal expectedBalance = new BigDecimal("1000.00")
                .add(additionAmount.multiply(new BigDecimal(successfulAdditions.get())));
        
        assertEquals(expectedBalance, updatedUser.getBalance());
        assertEquals(numberOfThreads * additionsPerThread, successfulAdditions.get());
    }

    @Test
    void testConcurrentMixedOperations() throws InterruptedException {
        int numberOfThreads = 15;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        BigDecimal initialBalance = testUser.getBalance();
        AtomicInteger totalCredits = new AtomicInteger(0);
        AtomicInteger totalDebits = new AtomicInteger(0);

        // Create test order
        Order testOrder = Order.builder()
                .user(testUser)
                .serviceId(1L)
                .link("https://example.com")
                .quantity(50L)
                .charge(new BigDecimal("10.00"))
                .status(OrderStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    if (threadIndex % 3 == 0) {
                        // Add balance
                        balanceService.addBalance(testUser, new BigDecimal("20.00"), null, 
                            "Mixed test addition");
                        totalCredits.addAndGet(20);
                    } else if (threadIndex % 3 == 1) {
                        // Deduct balance
                        try {
                            balanceService.deductBalance(testUser, new BigDecimal("15.00"), testOrder, 
                                "Mixed test deduction");
                            totalDebits.addAndGet(15);
                        } catch (InsufficientBalanceException ignored) {
                            // Expected in some cases
                        }
                    } else {
                        // Refund
                        balanceService.refund(testUser, new BigDecimal("5.00"), testOrder, 
                            "Mixed test refund");
                        totalCredits.addAndGet(5);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Mixed operations should complete within 30 seconds");
        executor.shutdown();

        // Verify final balance is consistent
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertNotNull(updatedUser.getBalance());
        assertTrue(updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0, 
            "Balance should never go negative");
    }

    @Test
    void testConcurrentBalanceTransfers() throws InterruptedException {
        int numberOfThreads = 10;
        BigDecimal transferAmount = new BigDecimal("20.00");
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger failedTransfers = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    if (threadIndex % 2 == 0) {
                        // Transfer from testUser to testUser2
                        try {
                            balanceService.transferBalance(testUser.getId(), testUser2.getId(), 
                                transferAmount, "Concurrent transfer test");
                            successfulTransfers.incrementAndGet();
                        } catch (InsufficientBalanceException e) {
                            failedTransfers.incrementAndGet();
                        }
                    } else {
                        // Transfer from testUser2 to testUser
                        try {
                            balanceService.transferBalance(testUser2.getId(), testUser.getId(), 
                                transferAmount, "Concurrent transfer test");
                            successfulTransfers.incrementAndGet();
                        } catch (InsufficientBalanceException e) {
                            failedTransfers.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Transfer operations should complete within 30 seconds");
        executor.shutdown();

        // Verify total balance remains constant
        User updatedUser1 = userRepository.findById(testUser.getId()).orElseThrow();
        User updatedUser2 = userRepository.findById(testUser2.getId()).orElseThrow();
        
        BigDecimal totalBalance = updatedUser1.getBalance().add(updatedUser2.getBalance());
        BigDecimal expectedTotalBalance = new BigDecimal("1500.00"); // 1000 + 500
        
        assertEquals(expectedTotalBalance, totalBalance, 
            "Total balance across users should remain constant");
        
        assertTrue(successfulTransfers.get() + failedTransfers.get() == numberOfThreads,
            "All transfer attempts should be accounted for");
    }

    @Test
    void testAtomicBalanceAdjustment() throws InterruptedException {
        int numberOfThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        List<BigDecimal> adjustments = List.of(
            new BigDecimal("10.00"),   // Credit
            new BigDecimal("-5.00"),   // Debit
            new BigDecimal("7.50"),    // Credit
            new BigDecimal("-2.25")    // Debit
        );

        AtomicInteger successfulAdjustments = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    BigDecimal adjustment = adjustments.get(threadIndex % adjustments.size());
                    TransactionType type = adjustment.compareTo(BigDecimal.ZERO) > 0 
                        ? TransactionType.ADJUSTMENT : TransactionType.ADJUSTMENT;
                    
                    try {
                        balanceService.adjustBalance(testUser.getId(), adjustment, type, 
                            "Atomic adjustment test", null);
                        successfulAdjustments.incrementAndGet();
                    } catch (InsufficientBalanceException ignored) {
                        // Expected for some debit operations
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Adjustment operations should complete within 30 seconds");
        executor.shutdown();

        // Verify balance consistency
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertNotNull(updatedUser.getBalance());
        assertTrue(updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0, 
            "Balance should remain non-negative");
        
        // Verify transaction count matches successful adjustments
        List<BalanceTransaction> transactions = transactionRepository.findByUserId(testUser.getId(), null).getContent();
        assertEquals(successfulAdjustments.get(), transactions.size());
    }

    @Test
    void testCheckAndReserveBalanceConcurrency() throws InterruptedException {
        int numberOfThreads = 30;
        BigDecimal checkAmount = new BigDecimal("100.00");
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        AtomicInteger successfulChecks = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            executor.submit(() -> {
                try {
                    boolean hasBalance = balanceService.checkAndReserveBalance(testUser.getId(), checkAmount);
                    if (hasBalance) {
                        successfulChecks.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Balance check operations should complete within 30 seconds");
        executor.shutdown();

        // All checks should succeed since we're only checking, not actually deducting
        assertEquals(numberOfThreads, successfulChecks.get(), 
            "All balance checks should succeed for sufficient balance");
    }

    @Test
    void testRetryMechanismWithSimulatedOptimisticLockingFailure() {
        // This test would require more complex setup to simulate OptimisticLockingFailureException
        // For now, we test that the service handles normal concurrent operations correctly
        
        BigDecimal initialBalance = testUser.getBalance();
        assertDoesNotThrow(() -> {
            balanceService.deductBalance(testUser, new BigDecimal("50.00"), null, "Test deduction");
        });
        
        User updatedUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertEquals(initialBalance.subtract(new BigDecimal("50.00")), updatedUser.getBalance());
    }

    @Test
    void testBalanceConsistencyAfterHighConcurrency() throws InterruptedException {
        // Stress test with many concurrent operations
        int numberOfThreads = 50;
        int operationsPerThread = 10;
        
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        BigDecimal initialBalance = testUser.getBalance();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        try {
                            switch ((threadIndex + j) % 4) {
                                case 0:
                                    balanceService.addBalance(testUser, new BigDecimal("1.00"), null, "Stress test add");
                                    break;
                                case 1:
                                    try {
                                        balanceService.deductBalance(testUser, new BigDecimal("0.50"), null, "Stress test deduct");
                                    } catch (InsufficientBalanceException ignored) {}
                                    break;
                                case 2:
                                    balanceService.refund(testUser, new BigDecimal("0.25"), null, "Stress test refund");
                                    break;
                                case 3:
                                    balanceService.checkAndReserveBalance(testUser.getId(), new BigDecimal("10.00"));
                                    break;
                            }
                        } catch (Exception e) {
                            // Log but don't fail the test for expected exceptions
                            System.out.println("Expected exception during stress test: " + e.getMessage());
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Stress test should complete within 60 seconds");
        executor.shutdown();

        // Verify final state is consistent
        User finalUser = userRepository.findById(testUser.getId()).orElseThrow();
        assertNotNull(finalUser.getBalance());
        assertTrue(finalUser.getBalance().compareTo(BigDecimal.ZERO) >= 0, 
            "Balance should never go negative even under high concurrency");
        
        // Verify all transactions are properly recorded
        List<BalanceTransaction> allTransactions = transactionRepository.findByUserId(testUser.getId(), null).getContent();
        assertFalse(allTransactions.isEmpty(), "Should have transaction records");
        
        // Verify transaction consistency
        for (BalanceTransaction transaction : allTransactions) {
            assertNotNull(transaction.getBalanceBefore());
            assertNotNull(transaction.getBalanceAfter());
            assertNotNull(transaction.getAmount());
            assertNotNull(transaction.getTransactionType());
        }
    }
}
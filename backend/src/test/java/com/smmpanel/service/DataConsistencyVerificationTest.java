package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.repository.*;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@Testcontainers
class DataConsistencyVerificationTest {

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
        registry.add("app.balance.retry.max-delay", () -> "2000");
        registry.add("app.balance.retry.multiplier", () -> "2.0");
        registry.add("app.transaction.monitoring.enabled", () -> "true");
    }

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceTransactionRepository transactionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    private List<User> testUsers;
    private com.smmpanel.entity.Service testService;
    private BigDecimal initialTotalBalance;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up previous test data
        transactionRepository.deleteAll();
        orderRepository.deleteAll();
        serviceRepository.deleteAll();
        userRepository.deleteAll();

        // Create test users with known initial balances
        testUsers = new ArrayList<>();
        initialTotalBalance = BigDecimal.ZERO;
        
        for (int i = 0; i < 10; i++) {
            BigDecimal userBalance = new BigDecimal("500.00");
            User user = User.builder()
                    .username("consistencyuser" + i)
                    .email("consistency" + i + "@test.com")
                    .passwordHash("password")
                    .role(UserRole.USER)
                    .balance(userBalance)
                    .totalSpent(BigDecimal.ZERO)
                    .createdAt(LocalDateTime.now())
                    .version(0L)
                    .build();
            testUsers.add(userRepository.save(user));
            initialTotalBalance = initialTotalBalance.add(userBalance);
        }

        // Create test service
        testService = com.smmpanel.entity.Service.builder()
                .name("Consistency Test Service")
                .pricePer1000(new BigDecimal("1.00"))
                .minOrder(1)
                .maxOrder(1000)
                .active(true)
                .description("Service for consistency testing")
                .build();
        testService = serviceRepository.save(testService);
    }

    @Test
    @Timeout(180)
    void testDataConsistencyAfterIntensiveConcurrentOperations() throws InterruptedException {
        log.info("Starting intensive concurrent operations consistency test");
        
        int numberOfThreads = 50;
        int operationsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger totalOperations = new AtomicInteger(0);
        AtomicInteger successfulOperations = new AtomicInteger(0);
        AtomicInteger failedOperations = new AtomicInteger(0);
        
        // Track operations for verification
        Map<String, AtomicInteger> operationCounts = new ConcurrentHashMap<>();
        operationCounts.put("credits", new AtomicInteger(0));
        operationCounts.put("debits", new AtomicInteger(0));
        operationCounts.put("transfers", new AtomicInteger(0));
        operationCounts.put("adjustments", new AtomicInteger(0));
        operationCounts.put("refunds", new AtomicInteger(0));

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        totalOperations.incrementAndGet();
                        
                        try {
                            User randomUser = testUsers.get(ThreadLocalRandom.current().nextInt(testUsers.size()));
                            
                            switch ((threadIndex + j) % 5) {
                                case 0: // Credit
                                    balanceService.addBalance(randomUser, new BigDecimal("5.00"), null, 
                                        "Consistency test credit");
                                    operationCounts.get("credits").incrementAndGet();
                                    successfulOperations.incrementAndGet();
                                    break;
                                    
                                case 1: // Debit
                                    Order order = createTestOrder(randomUser, new BigDecimal("3.00"));
                                    boolean deducted = balanceService.checkAndDeductBalance(randomUser, 
                                        new BigDecimal("3.00"), order, "Consistency test debit");
                                    if (deducted) {
                                        operationCounts.get("debits").incrementAndGet();
                                        successfulOperations.incrementAndGet();
                                    } else {
                                        failedOperations.incrementAndGet();
                                    }
                                    break;
                                    
                                case 2: // Transfer
                                    User fromUser = testUsers.get(ThreadLocalRandom.current().nextInt(testUsers.size()));
                                    User toUser;
                                    do {
                                        toUser = testUsers.get(ThreadLocalRandom.current().nextInt(testUsers.size()));
                                    } while (toUser.getId().equals(fromUser.getId()));
                                    
                                    try {
                                        balanceService.transferBalance(fromUser.getId(), toUser.getId(), 
                                            new BigDecimal("2.00"), "Consistency test transfer");
                                        operationCounts.get("transfers").incrementAndGet();
                                        successfulOperations.incrementAndGet();
                                    } catch (Exception e) {
                                        failedOperations.incrementAndGet();
                                    }
                                    break;
                                    
                                case 3: // Adjustment
                                    BigDecimal adjustment = ThreadLocalRandom.current().nextBoolean() ? 
                                        new BigDecimal("1.50") : new BigDecimal("-1.00");
                                    try {
                                        balanceService.adjustBalance(randomUser.getId(), adjustment, 
                                            TransactionType.ADJUSTMENT, "Consistency test adjustment", null);
                                        operationCounts.get("adjustments").incrementAndGet();
                                        successfulOperations.incrementAndGet();
                                    } catch (Exception e) {
                                        failedOperations.incrementAndGet();
                                    }
                                    break;
                                    
                                case 4: // Refund
                                    balanceService.refund(randomUser, new BigDecimal("1.25"), null, 
                                        "Consistency test refund");
                                    operationCounts.get("refunds").incrementAndGet();
                                    successfulOperations.incrementAndGet();
                                    break;
                            }
                            
                        } catch (Exception e) {
                            failedOperations.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(180, TimeUnit.SECONDS), "Consistency test should complete within 3 minutes");
        executor.shutdown();

        // Perform comprehensive consistency verification
        verifyDataConsistency(operationCounts);

        log.info("Intensive consistency test completed - Total: {}, Successful: {}, Failed: {}, " +
                "Operation counts: {}", 
                totalOperations.get(), successfulOperations.get(), failedOperations.get(), operationCounts);
    }

    @Test
    @Timeout(120)
    void testBalanceTransactionIntegrity() throws InterruptedException {
        log.info("Starting balance-transaction integrity test");
        
        int numberOfThreads = 30;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        Set<Long> processedTransactionIds = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateTransactions = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    User user = testUsers.get(threadIndex % testUsers.size());
                    
                    // Perform operations that create transactions
                    for (int j = 0; j < 5; j++) {
                        try {
                            if (j % 2 == 0) {
                                balanceService.addBalance(user, new BigDecimal("10.00"), null, 
                                    "Integrity test add " + threadIndex + "-" + j);
                            } else {
                                Order order = createTestOrder(user, new BigDecimal("5.00"));
                                balanceService.checkAndDeductBalance(user, new BigDecimal("5.00"), order, 
                                    "Integrity test deduct " + threadIndex + "-" + j);
                            }
                        } catch (Exception e) {
                            // Expected in some cases
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(120, TimeUnit.SECONDS), "Integrity test should complete within 2 minutes");
        executor.shutdown();

        // Verify transaction integrity
        List<BalanceTransaction> allTransactions = transactionRepository.findAll();
        
        // Check for duplicate transaction IDs
        Set<Long> transactionIds = new HashSet<>();
        for (BalanceTransaction transaction : allTransactions) {
            if (!transactionIds.add(transaction.getId())) {
                duplicateTransactions.incrementAndGet();
            }
        }
        
        assertEquals(0, duplicateTransactions.get(), "Should have no duplicate transaction IDs");

        // Verify each transaction has valid data
        for (BalanceTransaction transaction : allTransactions) {
            assertNotNull(transaction.getUser(), "Transaction should have a user");
            assertNotNull(transaction.getAmount(), "Transaction should have an amount");
            assertNotNull(transaction.getBalanceBefore(), "Transaction should have balanceBefore");
            assertNotNull(transaction.getBalanceAfter(), "Transaction should have balanceAfter");
            assertNotNull(transaction.getTransactionType(), "Transaction should have a type");
            assertNotNull(transaction.getCreatedAt(), "Transaction should have createdAt");
            
            // Verify balance calculation integrity
            BigDecimal expectedBalanceAfter = transaction.getBalanceBefore().add(transaction.getAmount());
            assertEquals(expectedBalanceAfter, transaction.getBalanceAfter(), 
                "BalanceAfter should equal BalanceBefore + Amount for transaction " + transaction.getId());
        }

        log.info("Integrity test completed - Total transactions: {}, No duplicates found, All calculations verified", 
                allTransactions.size());
    }

    @Test
    @Timeout(90)
    void testUserBalanceTotalSpentConsistency() throws InterruptedException {
        log.info("Starting user balance and totalSpent consistency test");
        
        int numberOfThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    User user = testUsers.get(threadIndex % testUsers.size());
                    
                    // Mix of credit and debit operations
                    for (int j = 0; j < 10; j++) {
                        try {
                            if (j % 3 == 0) {
                                // Credit - should not affect totalSpent
                                balanceService.addBalance(user, new BigDecimal("8.00"), null, 
                                    "TotalSpent test credit");
                            } else {
                                // Debit - should increase totalSpent
                                Order order = createTestOrder(user, new BigDecimal("4.00"));
                                balanceService.checkAndDeductBalance(user, new BigDecimal("4.00"), order, 
                                    "TotalSpent test debit");
                            }
                        } catch (Exception e) {
                            // Expected in some cases
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(90, TimeUnit.SECONDS), "TotalSpent consistency test should complete within 90 seconds");
        executor.shutdown();

        // Verify consistency for each user
        for (User originalUser : testUsers) {
            User updatedUser = userRepository.findById(originalUser.getId()).orElseThrow();
            
            // Calculate expected totalSpent from transactions
            List<BalanceTransaction> userTransactions = transactionRepository.findByUserId(originalUser.getId(), null).getContent();
            
            BigDecimal expectedTotalSpent = userTransactions.stream()
                .filter(t -> t.getAmount().compareTo(BigDecimal.ZERO) < 0) // Negative amounts are debits
                .map(t -> t.getAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            assertEquals(expectedTotalSpent, updatedUser.getTotalSpent(), 
                "TotalSpent should match sum of debit transactions for user: " + updatedUser.getUsername());
            
            // Verify balance is non-negative
            assertTrue(updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0, 
                "Balance should never be negative for user: " + updatedUser.getUsername());
        }

        log.info("TotalSpent consistency test completed - All users verified");
    }

    @Test
    @Timeout(120)
    void testOrderBalanceTransactionConsistency() throws InterruptedException {
        log.info("Starting order-balance-transaction consistency test");
        
        int numberOfThreads = 25;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicInteger ordersCreated = new AtomicInteger(0);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    User user = testUsers.get(threadIndex % testUsers.size());
                    
                    for (int j = 0; j < 8; j++) {
                        try {
                            BigDecimal orderAmount = new BigDecimal("6.50");
                            Order order = createTestOrder(user, orderAmount);
                            
                            boolean success = balanceService.checkAndDeductBalance(user, orderAmount, order, 
                                "Order consistency test");
                            
                            if (success) {
                                ordersCreated.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // Expected in some cases
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(120, TimeUnit.SECONDS), "Order consistency test should complete within 2 minutes");
        executor.shutdown();

        // Verify order-transaction consistency
        List<Order> allOrders = orderRepository.findAll();
        List<BalanceTransaction> allTransactions = transactionRepository.findAll();
        
        // Group transactions by order
        Map<Long, List<BalanceTransaction>> transactionsByOrder = allTransactions.stream()
                .filter(t -> t.getOrder() != null)
                .collect(Collectors.groupingBy(t -> t.getOrder().getId()));
        
        for (Order order : allOrders) {
            List<BalanceTransaction> orderTransactions = transactionsByOrder.get(order.getId());
            
            if (orderTransactions != null && !orderTransactions.isEmpty()) {
                // Should have exactly one transaction per successful order
                assertEquals(1, orderTransactions.size(), 
                    "Order should have exactly one transaction: " + order.getId());
                
                BalanceTransaction transaction = orderTransactions.get(0);
                
                // Verify transaction amount matches order charge (negative for debit)
                assertEquals(order.getCharge().negate(), transaction.getAmount(), 
                    "Transaction amount should match order charge for order: " + order.getId());
                
                // Verify transaction type
                assertEquals(TransactionType.ORDER_PAYMENT, transaction.getTransactionType(), 
                    "Transaction should be ORDER_PAYMENT type for order: " + order.getId());
                    
                // Verify user consistency
                assertEquals(order.getUser().getId(), transaction.getUser().getId(), 
                    "Transaction user should match order user for order: " + order.getId());
            }
        }

        log.info("Order consistency test completed - Orders: {}, All order-transaction relationships verified", 
                allOrders.size());
    }

    @Test
    @Timeout(60)
    void testConcurrentBalanceModificationDataRaces() throws InterruptedException {
        log.info("Starting concurrent balance modification data race test");
        
        // Use single user to maximize contention
        User testUser = testUsers.get(0);
        int numberOfThreads = 40;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch latch = new CountDownLatch(numberOfThreads);
        
        AtomicReference<BigDecimal> observedMinBalance = new AtomicReference<>(testUser.getBalance());
        AtomicReference<BigDecimal> observedMaxBalance = new AtomicReference<>(testUser.getBalance());
        List<BigDecimal> balanceSnapshots = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 5; j++) {
                        try {
                            if (j % 2 == 0) {
                                balanceService.addBalance(testUser, new BigDecimal("3.00"), null, 
                                    "Data race test credit");
                            } else {
                                Order order = createTestOrder(testUser, new BigDecimal("2.00"));
                                balanceService.checkAndDeductBalance(testUser, new BigDecimal("2.00"), order, 
                                    "Data race test debit");
                            }
                            
                            // Capture balance snapshot
                            User currentUser = userRepository.findById(testUser.getId()).orElseThrow();
                            BigDecimal currentBalance = currentUser.getBalance();
                            balanceSnapshots.add(currentBalance);
                            
                            // Track min/max observed balances
                            observedMinBalance.accumulateAndGet(currentBalance, 
                                (current, observed) -> current.compareTo(observed) < 0 ? current : observed);
                            observedMaxBalance.accumulateAndGet(currentBalance, 
                                (current, observed) -> current.compareTo(observed) > 0 ? current : observed);
                            
                        } catch (Exception e) {
                            // Expected in some cases
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Data race test should complete within 60 seconds");
        executor.shutdown();

        // Verify no data races occurred
        User finalUser = userRepository.findById(testUser.getId()).orElseThrow();
        
        // Ensure final balance is consistent with transactions
        List<BalanceTransaction> userTransactions = transactionRepository.findByUserId(testUser.getId(), null).getContent();
        
        BigDecimal calculatedBalance = new BigDecimal("500.00"); // Initial balance
        for (BalanceTransaction transaction : userTransactions) {
            calculatedBalance = calculatedBalance.add(transaction.getAmount());
        }
        
        assertEquals(calculatedBalance, finalUser.getBalance(), 
            "Final balance should match calculated balance from transactions");
        
        // Verify balance never went negative
        assertTrue(observedMinBalance.get().compareTo(BigDecimal.ZERO) >= 0, 
            "Balance should never have gone negative");
        
        // Verify all balance snapshots are valid
        for (BigDecimal snapshot : balanceSnapshots) {
            assertTrue(snapshot.compareTo(BigDecimal.ZERO) >= 0, 
                "All balance snapshots should be non-negative");
        }

        log.info("Data race test completed - Balance range: {} to {}, Final balance: {}, " +
                "Total snapshots: {}, All consistent", 
                observedMinBalance.get(), observedMaxBalance.get(), finalUser.getBalance(), 
                balanceSnapshots.size());
    }

    private void verifyDataConsistency(Map<String, AtomicInteger> operationCounts) {
        // 1. Verify total balance conservation
        BigDecimal finalTotalBalance = testUsers.stream()
                .map(u -> userRepository.findById(u.getId()).orElseThrow().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate expected balance change
        BigDecimal expectedCredits = new BigDecimal("5.00").multiply(new BigDecimal(operationCounts.get("credits").get()));
        BigDecimal expectedDebits = new BigDecimal("3.00").multiply(new BigDecimal(operationCounts.get("debits").get()));
        BigDecimal expectedRefunds = new BigDecimal("1.25").multiply(new BigDecimal(operationCounts.get("refunds").get()));
        
        // Adjustments are variable, so we'll calculate from actual transactions
        List<BalanceTransaction> adjustmentTransactions = transactionRepository.findAll().stream()
                .filter(t -> t.getTransactionType() == TransactionType.ADJUSTMENT)
                .toList();
        
        BigDecimal actualAdjustments = adjustmentTransactions.stream()
                .map(BalanceTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal expectedTotalBalance = initialTotalBalance
                .add(expectedCredits)
                .subtract(expectedDebits)
                .add(expectedRefunds)
                .add(actualAdjustments);

        // Note: Transfers don't change total balance across all users
        assertTrue(Math.abs(expectedTotalBalance.subtract(finalTotalBalance).doubleValue()) < 0.01, 
            String.format("Total balance conservation failed. Expected: %s, Actual: %s, Difference: %s", 
                expectedTotalBalance, finalTotalBalance, expectedTotalBalance.subtract(finalTotalBalance)));

        // 2. Verify all users have non-negative balances
        for (User user : testUsers) {
            User updatedUser = userRepository.findById(user.getId()).orElseThrow();
            assertTrue(updatedUser.getBalance().compareTo(BigDecimal.ZERO) >= 0, 
                "User balance should not be negative: " + updatedUser.getUsername());
        }

        // 3. Verify transaction count matches operations
        List<BalanceTransaction> allTransactions = transactionRepository.findAll();
        long expectedTransactionCount = operationCounts.values().stream()
                .mapToLong(AtomicInteger::get)
                .sum();
        
        // Allow some variance due to failed operations
        assertTrue(allTransactions.size() <= expectedTransactionCount, 
            "Transaction count should not exceed successful operations");

        // 4. Verify transaction integrity
        for (BalanceTransaction transaction : allTransactions) {
            assertNotNull(transaction.getUser());
            assertNotNull(transaction.getAmount());
            assertNotNull(transaction.getBalanceBefore());
            assertNotNull(transaction.getBalanceAfter());
            assertNotNull(transaction.getTransactionType());
            
            // Verify balance calculation
            BigDecimal expectedBalanceAfter = transaction.getBalanceBefore().add(transaction.getAmount());
            assertEquals(expectedBalanceAfter, transaction.getBalanceAfter(), 
                "Transaction balance calculation error for transaction " + transaction.getId());
        }

        log.info("Data consistency verification passed - Total balance: {}, Transactions: {}, " +
                "All users have valid balances", finalTotalBalance, allTransactions.size());
    }

    private Order createTestOrder(User user, BigDecimal amount) {
        Order order = Order.builder()
                .user(user)
                .service(testService)
                .link("https://consistency-test.com/" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(10000))
                .quantity(100)
                .charge(amount)
                .status(OrderStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        return orderRepository.save(order);
    }
}
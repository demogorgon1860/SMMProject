package com.smmpanel.service;

import com.smmpanel.entity.*;
import com.smmpanel.repository.BalanceTransactionRepository;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.BalanceAuditService.*;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@Testcontainers
class BalanceAuditServiceTest {

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
    }

    @Autowired
    private BalanceAuditService balanceAuditService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceTransactionRepository transactionRepository;

    private User testUser;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up previous test data
        transactionRepository.deleteAll();
        userRepository.deleteAll();

        // Create test user
        testUser = User.builder()
                .username("audituser")
                .email("audit@test.com")
                .passwordHash("password")
                .role(UserRole.USER)
                .balance(new BigDecimal("1000.00"))
                .totalSpent(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .version(0L)
                .build();
        testUser = userRepository.save(testUser);
    }

    @Test
    @Timeout(30)
    void testCreateAuditEntry() {
        log.info("Testing audit entry creation");

        BigDecimal amount = new BigDecimal("100.00");
        BigDecimal balanceBefore = testUser.getBalance();
        BigDecimal balanceAfter = balanceBefore.subtract(amount);

        BalanceTransaction transaction = balanceAuditService.createAuditEntry(
                testUser,
                amount.negate(),
                balanceBefore,
                balanceAfter,
                TransactionType.ORDER_PAYMENT,
                "Test order payment",
                null,
                null,
                "TEST-REF-001",
                "TEST_SYSTEM",
                "192.168.1.1",
                "Test User Agent",
                "session-123"
        );

        assertNotNull(transaction);
        assertNotNull(transaction.getId());
        assertNotNull(transaction.getTransactionId());
        assertNotNull(transaction.getAuditHash());
        assertEquals(testUser.getId(), transaction.getUser().getId());
        assertEquals(amount.negate(), transaction.getAmount());
        assertEquals(balanceBefore, transaction.getBalanceBefore());
        assertEquals(balanceAfter, transaction.getBalanceAfter());
        assertEquals(TransactionType.ORDER_PAYMENT, transaction.getTransactionType());
        assertEquals("TEST-REF-001", transaction.getReferenceId());
        assertEquals("TEST_SYSTEM", transaction.getSourceSystem());
        assertEquals("192.168.1.1", transaction.getIpAddress());
        assertEquals("Test User Agent", transaction.getUserAgent());
        assertEquals("session-123", transaction.getSessionId());
        assertEquals(BalanceTransaction.ReconciliationStatus.PENDING, transaction.getReconciliationStatus());

        log.info("Audit entry created successfully with ID: {}", transaction.getId());
    }

    @Test
    @Timeout(30)
    void testBalanceReconciliation() {
        log.info("Testing balance reconciliation");

        // Create a series of transactions
        createTestTransactions();

        // Perform reconciliation
        BalanceReconciliation reconciliation = balanceAuditService.reconcileUserBalance(testUser.getId());

        assertNotNull(reconciliation);
        assertEquals(testUser.getId(), reconciliation.getUserId());
        assertEquals(testUser.getUsername(), reconciliation.getUsername());
        assertNotNull(reconciliation.getCurrentBalance());
        assertNotNull(reconciliation.getCalculatedBalance());
        assertNotNull(reconciliation.getDiscrepancy());
        assertTrue(reconciliation.getTransactionCount() > 0);
        assertNotNull(reconciliation.getReconciliationTime());
        
        // For our test data, reconciliation should be successful
        assertTrue(reconciliation.getIsReconciled(), "Balance should be reconciled");
        assertEquals(BigDecimal.ZERO, reconciliation.getDiscrepancy(), "Discrepancy should be zero");

        log.info("Balance reconciliation completed - Reconciled: {}, Discrepancy: {}", 
                reconciliation.getIsReconciled(), reconciliation.getDiscrepancy());
    }

    @Test
    @Timeout(60)
    void testDailyBalanceVerification() throws Exception {
        log.info("Testing daily balance verification");

        // Create test transactions for today
        createTestTransactions();

        // Perform daily verification
        CompletableFuture<DailyBalanceReport> reportFuture = 
                balanceAuditService.performDailyBalanceVerification(LocalDate.now());

        DailyBalanceReport report = reportFuture.get();

        assertNotNull(report);
        assertEquals(LocalDate.now(), report.getVerificationDate());
        assertNotNull(report.getVerificationTime());
        assertTrue(report.getTotalUsers() >= 1);
        assertNotNull(report.getTotalSystemBalance());
        assertNotNull(report.getTotalCalculatedBalance());
        assertNotNull(report.getSystemDiscrepancy());
        assertNotNull(report.getReconciliations());
        assertNotNull(report.getSystemWideIssues());

        // Check that our test user is included
        boolean testUserFound = report.getReconciliations().stream()
                .anyMatch(r -> r.getUserId().equals(testUser.getId()));
        assertTrue(testUserFound, "Test user should be included in verification");

        log.info("Daily verification completed - Users: {}, Reconciled: {}, Discrepancies: {}", 
                report.getTotalUsers(), report.getUsersReconciled(), report.getUsersWithDiscrepancies());
    }

    @Test
    @Timeout(30)
    void testAuditTrailIntegrityVerification() {
        log.info("Testing audit trail integrity verification");

        // Create test transactions
        createTestTransactions();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneHourAgo = now.minusHours(1);

        // Verify integrity
        AuditTrailIntegrityReport report = balanceAuditService.verifyAuditTrailIntegrity(
                testUser.getId(), oneHourAgo, now);

        assertNotNull(report);
        assertEquals(testUser.getId(), report.getUserId());
        assertEquals(testUser.getUsername(), report.getUsername());
        assertEquals(oneHourAgo, report.getPeriodStart());
        assertEquals(now, report.getPeriodEnd());
        assertTrue(report.getTotalTransactions() > 0);
        assertNotNull(report.getHashMismatches());
        assertNotNull(report.getChainBreaks());
        assertNotNull(report.getIsIntegrityValid());
        assertNotNull(report.getIntegrityIssues());
        assertNotNull(report.getVerificationTime());

        // For our test data, integrity should be valid
        assertTrue(report.getIsIntegrityValid(), "Audit trail integrity should be valid");
        assertEquals(0, report.getHashMismatches(), "Should have no hash mismatches");
        assertEquals(0, report.getChainBreaks(), "Should have no chain breaks");

        log.info("Integrity verification completed - Valid: {}, Hash mismatches: {}, Chain breaks: {}", 
                report.getIsIntegrityValid(), report.getHashMismatches(), report.getChainBreaks());
    }

    @Test
    @Timeout(30)
    void testAuditTrailReportGeneration() {
        log.info("Testing audit trail report generation");

        // Create test transactions
        createTestTransactions();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneHourAgo = now.minusHours(1);

        // Generate report
        AuditTrailReport report = balanceAuditService.generateAuditTrailReport(
                testUser.getId(), oneHourAgo, now);

        assertNotNull(report);
        assertEquals(testUser.getId(), report.getUserId());
        assertEquals(testUser.getUsername(), report.getUsername());
        assertEquals(oneHourAgo, report.getPeriodStart());
        assertEquals(now, report.getPeriodEnd());
        assertTrue(report.getTotalTransactions() > 0);
        assertNotNull(report.getStartingBalance());
        assertNotNull(report.getEndingBalance());
        assertNotNull(report.getNetChange());
        assertNotNull(report.getTransactionTypeCount());
        assertNotNull(report.getTransactionTypeAmount());
        assertNotNull(report.getTransactions());

        // Verify transaction types are properly counted
        assertTrue(report.getTransactionTypeCount().size() > 0, "Should have transaction type counts");
        assertTrue(report.getTransactionTypeAmount().size() > 0, "Should have transaction type amounts");

        log.info("Audit trail report generated - Transactions: {}, Net change: {}", 
                report.getTotalTransactions(), report.getNetChange());
    }

    @Test
    @Timeout(30)
    void testAuditTrailHashChaining() {
        log.info("Testing audit trail hash chaining");

        // Create first transaction
        BalanceTransaction firstTxn = balanceAuditService.createAuditEntry(
                testUser,
                new BigDecimal("50.00"),
                testUser.getBalance(),
                testUser.getBalance().add(new BigDecimal("50.00")),
                TransactionType.DEPOSIT,
                "First transaction",
                null, null, "CHAIN-TEST-001", "TEST_SYSTEM",
                null, null, null);

        assertNotNull(firstTxn.getAuditHash());
        assertNull(firstTxn.getPreviousTransactionHash()); // First transaction has no previous hash

        // Create second transaction
        BalanceTransaction secondTxn = balanceAuditService.createAuditEntry(
                testUser,
                new BigDecimal("-25.00"),
                testUser.getBalance().add(new BigDecimal("50.00")),
                testUser.getBalance().add(new BigDecimal("25.00")),
                TransactionType.ORDER_PAYMENT,
                "Second transaction",
                null, null, "CHAIN-TEST-002", "TEST_SYSTEM",
                null, null, null);

        assertNotNull(secondTxn.getAuditHash());
        assertNotNull(secondTxn.getPreviousTransactionHash());
        assertEquals(firstTxn.getAuditHash(), secondTxn.getPreviousTransactionHash());

        log.info("Hash chaining verified - First hash: {}, Second previous hash: {}", 
                firstTxn.getAuditHash().substring(0, 8), 
                secondTxn.getPreviousTransactionHash().substring(0, 8));
    }

    @Test
    @Timeout(30)
    void testBalanceReconciliationWithDiscrepancy() {
        log.info("Testing balance reconciliation with discrepancy");

        // Create transactions normally
        createTestTransactions();

        // Manually modify user balance to create discrepancy
        testUser.setBalance(testUser.getBalance().add(new BigDecimal("999.99")));
        userRepository.save(testUser);

        // Perform reconciliation
        BalanceReconciliation reconciliation = balanceAuditService.reconcileUserBalance(testUser.getId());

        assertNotNull(reconciliation);
        assertFalse(reconciliation.getIsReconciled(), "Balance should not be reconciled");
        assertTrue(reconciliation.getDiscrepancy().compareTo(BigDecimal.ZERO) != 0, "Should have discrepancy");

        log.info("Discrepancy test completed - Reconciled: {}, Discrepancy: {}", 
                reconciliation.getIsReconciled(), reconciliation.getDiscrepancy());
    }

    @Test
    @Timeout(30)
    void testTransactionIdUniqueness() {
        log.info("Testing transaction ID uniqueness");

        // Create multiple transactions rapidly
        for (int i = 0; i < 10; i++) {
            BalanceTransaction txn = balanceAuditService.createAuditEntry(
                    testUser,
                    new BigDecimal("1.00"),
                    testUser.getBalance(),
                    testUser.getBalance().add(new BigDecimal("1.00")),
                    TransactionType.DEPOSIT,
                    "Uniqueness test " + i,
                    null, null, "UNIQUE-" + i, "TEST_SYSTEM",
                    null, null, null);
            
            assertNotNull(txn.getTransactionId());
            assertTrue(txn.getTransactionId().startsWith("TXN-"));
        }

        // Verify all transaction IDs are unique
        List<BalanceTransaction> transactions = transactionRepository.findByUserOrderByCreatedAtAsc(testUser);
        long uniqueTransactionIds = transactions.stream()
                .map(BalanceTransaction::getTransactionId)
                .distinct()
                .count();

        assertEquals(transactions.size(), uniqueTransactionIds, "All transaction IDs should be unique");

        log.info("Transaction ID uniqueness verified - {} unique IDs out of {} transactions", 
                uniqueTransactionIds, transactions.size());
    }

    private void createTestTransactions() {
        BigDecimal currentBalance = testUser.getBalance();

        // Transaction 1: Deposit
        BalanceTransaction deposit = balanceAuditService.createAuditEntry(
                testUser,
                new BigDecimal("200.00"),
                currentBalance,
                currentBalance.add(new BigDecimal("200.00")),
                TransactionType.DEPOSIT,
                "Test deposit",
                null, null, "TEST-DEPOSIT-001", "TEST_SYSTEM",
                "127.0.0.1", "Test Agent", "test-session");

        currentBalance = currentBalance.add(new BigDecimal("200.00"));

        // Transaction 2: Order payment
        balanceAuditService.createAuditEntry(
                testUser,
                new BigDecimal("-150.00"),
                currentBalance,
                currentBalance.subtract(new BigDecimal("150.00")),
                TransactionType.ORDER_PAYMENT,
                "Test order payment",
                null, null, "TEST-ORDER-001", "TEST_SYSTEM",
                "127.0.0.1", "Test Agent", "test-session");

        currentBalance = currentBalance.subtract(new BigDecimal("150.00"));

        // Transaction 3: Refund
        balanceAuditService.createAuditEntry(
                testUser,
                new BigDecimal("25.00"),
                currentBalance,
                currentBalance.add(new BigDecimal("25.00")),
                TransactionType.REFUND,
                "Test refund",
                null, null, "TEST-REFUND-001", "TEST_SYSTEM",
                "127.0.0.1", "Test Agent", "test-session");

        // Update user balance to match final transaction
        testUser.setBalance(currentBalance.add(new BigDecimal("25.00")));
        userRepository.save(testUser);
    }
}
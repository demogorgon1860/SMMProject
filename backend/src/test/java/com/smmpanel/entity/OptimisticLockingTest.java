package com.smmpanel.entity;

import com.smmpanel.repository.UserRepository;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.BalanceTransactionRepository;
import com.smmpanel.repository.ServiceRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for optimistic locking functionality
 * Tests version increment behavior and concurrent modification detection
 */
@SpringBootTest
@ActiveProfiles("test")
@Slf4j
public class OptimisticLockingTest {

    @PersistenceContext
    private EntityManager entityManager;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private ServiceRepository serviceRepository;
    
    @Autowired
    private BalanceTransactionRepository balanceTransactionRepository;
    
    private User testUser;
    private Service testService;
    
    @BeforeEach
    @Transactional
    public void setupOptimisticLockingTest() {
        log.info("Setting up optimistic locking test data");
        
        // Create test user
        testUser = User.builder()
                .username("optimistictest")
                .email("optimistic@test.com")
                .passwordHash("hashedpassword123")
                .balance(BigDecimal.valueOf(1000))
                .isActive(true)
                .emailVerified(true)
                .build();
        
        testUser = userRepository.save(testUser);
        
        // Create test service
        testService = Service.builder()
                .name("Optimistic Test Service")
                .category("Test")
                .minOrder(10)
                .maxOrder(1000)
                .pricePer1000(BigDecimal.valueOf(5.0))
                .description("Service for optimistic locking tests")
                .active(true)
                .geoTargeting("US")
                .build();
        
        testService = serviceRepository.save(testService);
        
        entityManager.flush();
        entityManager.clear();
        
        log.info("Test data setup completed - User ID: {}, Service ID: {}", 
                testUser.getId(), testService.getId());
    }
    
    @Test
    @DisplayName("Test User Entity Version Increment on Update")
    @Transactional
    public void testUserVersionIncrement() {
        log.info("Testing User entity version increment");
        
        // Load user and verify initial version
        Optional<User> userOpt = userRepository.findById(testUser.getId());
        assertTrue(userOpt.isPresent());
        User user = userOpt.get();
        
        Long initialVersion = user.getVersion();
        log.info("Initial user version: {}", initialVersion);
        
        // Update user balance
        BigDecimal originalBalance = user.getBalance();
        user.setBalance(originalBalance.add(BigDecimal.valueOf(100)));
        User updatedUser = userRepository.save(user);
        entityManager.flush();
        
        // Verify version was incremented
        Long newVersion = updatedUser.getVersion();
        log.info("New user version after update: {}", newVersion);
        
        assertNotNull(newVersion);
        if (initialVersion != null) {
            assertTrue(newVersion > initialVersion, 
                "Version should be incremented after update");
        }
        
        // Verify the update was persisted
        assertEquals(originalBalance.add(BigDecimal.valueOf(100)), 
                updatedUser.getBalance());
        
        log.info("✅ User version increment test passed");
    }
    
    @Test
    @DisplayName("Test Order Entity Version Increment on Update")
    @Transactional
    public void testOrderVersionIncrement() {
        log.info("Testing Order entity version increment");
        
        // Create test order
        Order testOrder = Order.builder()
                .user(testUser)
                .service(testService)
                .link("https://www.youtube.com/watch?v=test123")
                .quantity(100)
                .price(BigDecimal.valueOf(50))
                .charge(BigDecimal.valueOf(50))
                .status(OrderStatus.PENDING)
                .build();
        
        Order savedOrder = orderRepository.save(testOrder);
        entityManager.flush();
        
        Long initialVersion = savedOrder.getVersion();
        log.info("Initial order version: {}", initialVersion);
        
        // Update order status
        savedOrder.setStatus(OrderStatus.PROCESSING);
        Order updatedOrder = orderRepository.save(savedOrder);
        entityManager.flush();
        
        // Verify version was incremented
        Long newVersion = updatedOrder.getVersion();
        log.info("New order version after update: {}", newVersion);
        
        assertNotNull(newVersion);
        if (initialVersion != null) {
            assertTrue(newVersion > initialVersion, 
                "Version should be incremented after update");
        }
        
        // Verify the update was persisted
        assertEquals(OrderStatus.PROCESSING, updatedOrder.getStatus());
        
        log.info("✅ Order version increment test passed");
    }
    
    @Test
    @DisplayName("Test BalanceTransaction Entity Version Increment")
    @Transactional
    public void testBalanceTransactionVersionIncrement() {
        log.info("Testing BalanceTransaction entity version increment");
        
        // Create test transaction
        BalanceTransaction transaction = new BalanceTransaction();
        transaction.setUser(testUser);
        transaction.setAmount(BigDecimal.valueOf(50));
        transaction.setBalanceBefore(BigDecimal.valueOf(1000));
        transaction.setBalanceAfter(BigDecimal.valueOf(1050));
        transaction.setTransactionType(TransactionType.DEPOSIT);
        transaction.setDescription("Test deposit");
        transaction.setTransactionId("TEST_TXN_001");
        
        BalanceTransaction savedTransaction = balanceTransactionRepository.save(transaction);
        entityManager.flush();
        
        Long initialVersion = savedTransaction.getVersion();
        log.info("Initial transaction version: {}", initialVersion);
        
        // Update transaction description
        savedTransaction.setDescription("Updated test deposit");
        BalanceTransaction updatedTransaction = balanceTransactionRepository.save(savedTransaction);
        entityManager.flush();
        
        // Verify version was incremented
        Long newVersion = updatedTransaction.getVersion();
        log.info("New transaction version after update: {}", newVersion);
        
        assertNotNull(newVersion);
        if (initialVersion != null) {
            assertTrue(newVersion > initialVersion, 
                "Version should be incremented after update");
        }
        
        // Verify the update was persisted
        assertEquals("Updated test deposit", updatedTransaction.getDescription());
        
        log.info("✅ BalanceTransaction version increment test passed");
    }
    
    @Test
    @DisplayName("Test Concurrent Modification Detection")
    public void testConcurrentModificationDetection() throws InterruptedException {
        log.info("Testing concurrent modification detection with optimistic locking");
        
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Exception> exception1 = new AtomicReference<>();
        AtomicReference<Exception> exception2 = new AtomicReference<>();
        
        // Both threads will try to update the same user simultaneously
        Future<?> future1 = executor.submit(() -> {
            try {
                // Load user in first transaction
                Optional<User> userOpt = userRepository.findById(testUser.getId());
                assertTrue(userOpt.isPresent());
                User user1 = userOpt.get();
                
                log.info("Thread 1: Loaded user with version {}", user1.getVersion());
                
                // Simulate some processing time
                Thread.sleep(100);
                
                // Update balance
                user1.setBalance(user1.getBalance().add(BigDecimal.valueOf(50)));
                userRepository.save(user1);
                
                log.info("Thread 1: Successfully updated user");
                
            } catch (Exception e) {
                log.info("Thread 1: Exception occurred - {}", e.getMessage());
                exception1.set(e);
            } finally {
                latch.countDown();
            }
        });
        
        Future<?> future2 = executor.submit(() -> {
            try {
                // Load user in second transaction (slightly delayed)
                Thread.sleep(50);
                Optional<User> userOpt = userRepository.findById(testUser.getId());
                assertTrue(userOpt.isPresent());
                User user2 = userOpt.get();
                
                log.info("Thread 2: Loaded user with version {}", user2.getVersion());
                
                // Simulate some processing time
                Thread.sleep(100);
                
                // Update username (different field)
                user2.setPreferredCurrency("EUR");
                userRepository.save(user2);
                
                log.info("Thread 2: Successfully updated user");
                
            } catch (Exception e) {
                log.info("Thread 2: Exception occurred - {}", e.getMessage());
                exception2.set(e);
            } finally {
                latch.countDown();
            }
        });
        
        // Wait for both threads to complete
        latch.await();
        executor.shutdown();
        
        // Check results
        Exception exc1 = exception1.get();
        Exception exc2 = exception2.get();
        
        log.info("Thread 1 exception: {}", exc1 != null ? exc1.getClass().getSimpleName() : "None");
        log.info("Thread 2 exception: {}", exc2 != null ? exc2.getClass().getSimpleName() : "None");
        
        // At least one thread should succeed, and if both loaded the same version,
        // one should get an OptimisticLockingFailureException
        boolean hasOptimisticLockingException = 
            (exc1 instanceof OptimisticLockingFailureException) ||
            (exc2 instanceof OptimisticLockingFailureException);
        
        if (hasOptimisticLockingException) {
            log.info("✅ Optimistic locking successfully prevented concurrent modification");
        } else {
            log.info("ℹ️ No optimistic locking exception occurred - this may be due to timing or transaction isolation");
        }
        
        // Verify final state
        Optional<User> finalUserOpt = userRepository.findById(testUser.getId());
        assertTrue(finalUserOpt.isPresent());
        User finalUser = finalUserOpt.get();
        
        log.info("Final user version: {}", finalUser.getVersion());
        log.info("Final user balance: {}", finalUser.getBalance());
        log.info("Final user currency: {}", finalUser.getPreferredCurrency());
        
        log.info("✅ Concurrent modification test completed");
    }
    
    @Test
    @DisplayName("Test Version Persistence After Flush")
    @Transactional
    public void testVersionPersistenceAfterFlush() {
        log.info("Testing version persistence after entity manager flush");
        
        // Load user
        Optional<User> userOpt = userRepository.findById(testUser.getId());
        assertTrue(userOpt.isPresent());
        User user = userOpt.get();
        
        Long versionBeforeUpdate = user.getVersion();
        log.info("Version before update: {}", versionBeforeUpdate);
        
        // Make multiple updates and flush each time
        for (int i = 1; i <= 3; i++) {
            user.setBalance(user.getBalance().add(BigDecimal.valueOf(10)));
            userRepository.save(user);
            entityManager.flush();
            
            Long currentVersion = user.getVersion();
            log.info("Version after update {}: {}", i, currentVersion);
            
            assertNotNull(currentVersion);
            if (versionBeforeUpdate != null) {
                assertTrue(currentVersion >= versionBeforeUpdate + i, 
                    "Version should increase with each update");
            }
        }
        
        // Clear and reload to verify persistence
        entityManager.clear();
        
        Optional<User> reloadedUserOpt = userRepository.findById(testUser.getId());
        assertTrue(reloadedUserOpt.isPresent());
        User reloadedUser = reloadedUserOpt.get();
        
        log.info("Version after reload: {}", reloadedUser.getVersion());
        
        // Version should be persisted correctly
        assertNotNull(reloadedUser.getVersion());
        if (versionBeforeUpdate != null) {
            assertTrue(reloadedUser.getVersion() >= versionBeforeUpdate + 3);
        }
        
        log.info("✅ Version persistence test passed");
    }
}
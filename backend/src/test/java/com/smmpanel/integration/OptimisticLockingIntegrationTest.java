package com.smmpanel.integration;

import com.smmpanel.entity.User;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.service.UserService;
import com.smmpanel.service.OptimisticLockingService.OptimisticLockingException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for optimistic locking with real database operations
 * Tests the complete flow from database migration to service layer
 */
@SpringBootTest
@ActiveProfiles("test")
@Slf4j
public class OptimisticLockingIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private ServiceRepository serviceRepository;
    
    @Autowired
    private UserService userService;
    
    private User testUser;
    private Service testService;
    
    @BeforeEach
    @Transactional
    public void setupIntegrationTest() {
        log.info("Setting up optimistic locking integration test");
        
        // Create test user with initial balance
        testUser = User.builder()
                .username("integrationtest")
                .email("integration@test.com")
                .passwordHash("hashedpassword456")
                .balance(BigDecimal.valueOf(500))
                .isActive(true)
                .emailVerified(true)
                .build();
        
        testUser = userRepository.save(testUser);
        
        // Create test service
        testService = Service.builder()
                .name("Integration Test Service")
                .category("Test")
                .minOrder(10)
                .maxOrder(1000)
                .pricePer1000(BigDecimal.valueOf(10.0))
                .description("Service for integration testing")
                .active(true)
                .geoTargeting("US")
                .build();
        
        testService = serviceRepository.save(testService);
        
        entityManager.flush();
        entityManager.clear();
        
        log.info("Integration test setup completed - User ID: {}, Service ID: {}", 
                testUser.getId(), testService.getId());
    }
    
    @Test
    @DisplayName("Test Database Version Column Exists and Functions")
    @Transactional
    public void testVersionColumnFunctionality() {
        log.info("Testing version column functionality with database");
        
        // Load user and verify version column exists
        Optional<User> userOpt = userRepository.findById(testUser.getId());
        assertTrue(userOpt.isPresent());
        User user = userOpt.get();
        
        // Version should be initialized (either 0 or null, depending on migration)
        Long initialVersion = user.getVersion();
        log.info("Initial user version from database: {}", initialVersion);
        
        // Update user and verify version changes
        user.setPreferredCurrency("EUR");
        User savedUser = userRepository.save(user);
        entityManager.flush();
        
        Long newVersion = savedUser.getVersion();
        log.info("New user version after update: {}", newVersion);
        
        // Version should have changed
        assertNotEquals(initialVersion, newVersion);
        assertNotNull(newVersion);
        
        log.info("✅ Version column functioning correctly");
    }
    
    @Test
    @DisplayName("Test UserService Balance Update with Optimistic Locking")
    @Transactional
    public void testUserServiceBalanceUpdate() {
        log.info("Testing UserService balance update with optimistic locking");
        
        // Get initial balance and version
        Optional<User> initialUserOpt = userRepository.findById(testUser.getId());
        assertTrue(initialUserOpt.isPresent());
        BigDecimal initialBalance = initialUserOpt.get().getBalance();
        Long initialVersion = initialUserOpt.get().getVersion();
        
        log.info("Initial balance: {}, version: {}", initialBalance, initialVersion);
        
        // Use UserService to update balance
        BigDecimal depositAmount = BigDecimal.valueOf(100);
        User updatedUser = userService.updateBalanceWithLocking(
                testUser.getId(), 
                depositAmount, 
                "Test deposit"
        );
        
        // Verify balance was updated
        assertEquals(initialBalance.add(depositAmount), updatedUser.getBalance());
        
        // Verify version was incremented
        assertNotEquals(initialVersion, updatedUser.getVersion());
        
        log.info("Final balance: {}, version: {}", 
                updatedUser.getBalance(), updatedUser.getVersion());
        
        log.info("✅ UserService balance update with locking successful");
    }
    
    @Test
    @DisplayName("Test Concurrent Balance Updates")
    public void testConcurrentBalanceUpdates() throws ExecutionException, InterruptedException {
        log.info("Testing concurrent balance updates");
        
        // Create two concurrent tasks that update the same user's balance
        CompletableFuture<User> future1 = CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Task 1: Starting balance update");
                return userService.updateBalanceWithLocking(
                        testUser.getId(), 
                        BigDecimal.valueOf(50), 
                        "Concurrent update 1"
                );
            } catch (Exception e) {
                log.info("Task 1: Exception - {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
        
        CompletableFuture<User> future2 = CompletableFuture.supplyAsync(() -> {
            try {
                // Small delay to increase chance of conflict
                Thread.sleep(10);
                log.info("Task 2: Starting balance update");
                return userService.updateBalanceWithLocking(
                        testUser.getId(), 
                        BigDecimal.valueOf(75), 
                        "Concurrent update 2"
                );
            } catch (Exception e) {
                log.info("Task 2: Exception - {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
        
        // Wait for both tasks to complete
        User result1 = null;
        User result2 = null;
        Exception exception1 = null;
        Exception exception2 = null;
        
        try {
            result1 = future1.get();
        } catch (ExecutionException e) {
            exception1 = (Exception) e.getCause();
        }
        
        try {
            result2 = future2.get();
        } catch (ExecutionException e) {
            exception2 = (Exception) e.getCause();
        }
        
        log.info("Task 1 result: {}, exception: {}", 
                result1 != null ? "Success" : "Failed", 
                exception1 != null ? exception1.getClass().getSimpleName() : "None");
        log.info("Task 2 result: {}, exception: {}", 
                result2 != null ? "Success" : "Failed", 
                exception2 != null ? exception2.getClass().getSimpleName() : "None");
        
        // At least one should succeed
        assertTrue(result1 != null || result2 != null, 
                "At least one concurrent update should succeed");
        
        // Check final state
        Optional<User> finalUserOpt = userRepository.findById(testUser.getId());
        assertTrue(finalUserOpt.isPresent());
        User finalUser = finalUserOpt.get();
        
        log.info("Final user balance: {}, version: {}", 
                finalUser.getBalance(), finalUser.getVersion());
        
        // Balance should have increased by at least one of the amounts
        assertTrue(finalUser.getBalance().compareTo(BigDecimal.valueOf(500)) > 0,
                "Balance should have increased from initial value");
        
        log.info("✅ Concurrent balance update test completed");
    }
    
    @Test
    @DisplayName("Test Order Entity Optimistic Locking")
    @Transactional
    public void testOrderOptimisticLocking() {
        log.info("Testing Order entity optimistic locking");
        
        // Create test order
        Order testOrder = Order.builder()
                .user(testUser)
                .service(testService)
                .link("https://www.youtube.com/watch?v=integration123")
                .quantity(100)
                .price(BigDecimal.valueOf(100))
                .charge(BigDecimal.valueOf(100))
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
        
        // Verify version increment
        Long newVersion = updatedOrder.getVersion();
        log.info("New order version: {}", newVersion);
        
        assertNotEquals(initialVersion, newVersion);
        assertEquals(OrderStatus.PROCESSING, updatedOrder.getStatus());
        
        log.info("✅ Order optimistic locking working correctly");
    }
    
    @Test
    @DisplayName("Test UserService Deduct Balance for Order")
    @Transactional
    public void testDeductBalanceForOrder() {
        log.info("Testing UserService deduct balance for order");
        
        // Get initial balance
        BigDecimal initialBalance = userRepository.findById(testUser.getId())
                .map(User::getBalance)
                .orElseThrow();
        
        log.info("Initial balance: {}", initialBalance);
        
        // Create order and deduct balance
        BigDecimal orderAmount = BigDecimal.valueOf(50);
        User updatedUser = userService.deductBalanceForOrder(
                testUser.getId(), 
                orderAmount, 
                999L
        );
        
        // Verify balance was deducted correctly
        BigDecimal expectedBalance = initialBalance.subtract(orderAmount);
        assertEquals(expectedBalance, updatedUser.getBalance());
        
        log.info("Balance after order deduction: {}", updatedUser.getBalance());
        
        log.info("✅ Balance deduction for order successful");
    }
    
    @Test
    @DisplayName("Test Insufficient Balance Handling")
    @Transactional
    public void testInsufficientBalanceHandling() {
        log.info("Testing insufficient balance handling");
        
        // Try to deduct more than available balance
        BigDecimal excessiveAmount = BigDecimal.valueOf(1000); // User only has 500
        
        assertThrows(IllegalArgumentException.class, () -> {
            userService.deductBalanceForOrder(testUser.getId(), excessiveAmount, 998L);
        });
        
        // Verify balance remains unchanged
        BigDecimal finalBalance = userRepository.findById(testUser.getId())
                .map(User::getBalance)
                .orElseThrow();
        
        assertEquals(BigDecimal.valueOf(500), finalBalance);
        
        log.info("✅ Insufficient balance properly handled");
    }
    
    @Test
    @DisplayName("Test Version Consistency After Multiple Operations")
    @Transactional
    public void testVersionConsistencyAfterMultipleOperations() {
        log.info("Testing version consistency after multiple operations");
        
        Long userId = testUser.getId();
        Long previousVersion = null;
        
        // Perform multiple operations and verify version increments
        for (int i = 1; i <= 5; i++) {
            User updatedUser = userService.updateBalanceWithLocking(
                    userId, 
                    BigDecimal.valueOf(10), 
                    "Operation " + i
            );
            
            Long currentVersion = updatedUser.getVersion();
            log.info("Operation {}: Version = {}, Balance = {}", 
                    i, currentVersion, updatedUser.getBalance());
            
            assertNotNull(currentVersion);
            
            if (previousVersion != null) {
                assertTrue(currentVersion > previousVersion, 
                        "Version should increase with each operation");
            }
            
            previousVersion = currentVersion;
        }
        
        // Final verification
        User finalUser = userRepository.findById(userId).orElseThrow();
        assertEquals(BigDecimal.valueOf(550), finalUser.getBalance()); // 500 + (5 * 10)
        
        log.info("✅ Version consistency maintained across multiple operations");
    }
}
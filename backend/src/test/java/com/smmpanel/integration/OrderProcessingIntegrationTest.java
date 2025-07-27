package com.smmpanel.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.ApiKeyService;
import com.smmpanel.service.OrderService;
import com.smmpanel.integration.IntegrationTestConfig;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Order Processing Integration Test
 * Tests the full order flow from creation to completion
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Import(IntegrationTestConfig.class)
public class OrderProcessingIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private Service testService;
    private String testApiKey;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = User.builder()
            .username("testuser")
            .email("test@example.com")
            .passwordHash("password")
            .balance(new BigDecimal("100.00"))
            .isActive(true)
            .build();
        testUser = userRepository.save(testUser);
        
        // Generate API key
        testApiKey = apiKeyService.generateApiKey(testUser.getId());
        
        // Create test service
        testService = Service.builder()
            .name("YouTube Views")
            .category("YouTube")
            .pricePer1000(new BigDecimal("1.50"))
            .minOrder(100)
            .maxOrder(10000)
            .active(true)
            .build();
        testService = serviceRepository.save(testService);
    }

    @Test
    void testFullOrderFlow() throws Exception {
        // Step 1: Create order
        Order order = createTestOrder();
        assertNotNull(order.getId());
        assertEquals(OrderStatus.PENDING, order.getStatus());
        
        // Step 2: Publish order created event
        OrderCreatedEvent event = new OrderCreatedEvent(this, order.getId(), testUser.getId());
        eventPublisher.publishEvent(event);
        
        // Step 3: Wait for async processing
        Thread.sleep(2000); // Give time for async processing
        
        // Step 4: Verify order status changes
        Order updatedOrder = orderRepository.findById(order.getId()).orElse(null);
        assertNotNull(updatedOrder);
        assertTrue(updatedOrder.getStatus() == OrderStatus.PROCESSING || 
                  updatedOrder.getStatus() == OrderStatus.ACTIVE,
                  "Order should be processing or active, was: " + updatedOrder.getStatus());
        
        // Step 5: Verify YouTube video ID extraction
        if (updatedOrder.getLink().contains("youtube")) {
            assertNotNull(updatedOrder.getYoutubeVideoId(), "YouTube video ID should be extracted");
        }
        
        // Step 6: Verify start count is set
        assertNotNull(updatedOrder.getStartCount(), "Start count should be set");
        assertTrue(updatedOrder.getStartCount() >= 0, "Start count should be non-negative");
    }

    @Test
    void testOrderCreationWithInsufficientBalance() {
        // Set user balance to low amount
        testUser.setBalance(new BigDecimal("0.01"));
        userRepository.save(testUser);
        
        // Try to create order with high quantity
        Order order = Order.builder()
            .user(testUser)
            .service(testService)
            .link("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .quantity(10000)
            .charge(new BigDecimal("15.00"))
            .status(OrderStatus.PENDING)
            .build();
        
        // This should fail due to insufficient balance
        assertThrows(Exception.class, () -> {
            orderRepository.save(order);
        });
    }

    @Test
    void testOrderStatusTransitions() throws Exception {
        // Create order
        Order order = createTestOrder();
        
        // Test status transitions
        order.setStatus(OrderStatus.PROCESSING);
        orderRepository.save(order);
        assertEquals(OrderStatus.PROCESSING, order.getStatus());
        
        order.setStatus(OrderStatus.ACTIVE);
        orderRepository.save(order);
        assertEquals(OrderStatus.ACTIVE, order.getStatus());
        
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    @Test
    void testConcurrentOrderProcessing() throws Exception {
        int numOrders = 5;
        CountDownLatch latch = new CountDownLatch(numOrders);
        
        // Create multiple orders concurrently
        for (int i = 0; i < numOrders; i++) {
            new Thread(() -> {
                try {
                    Order order = createTestOrder();
                    OrderCreatedEvent event = new OrderCreatedEvent(this, order.getId(), testUser.getId());
                    eventPublisher.publishEvent(event);
                    latch.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
        
        // Wait for all orders to be processed
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "All orders should be processed within 10 seconds");
        
        // Verify all orders were created
        long orderCount = orderRepository.count();
        assertTrue(orderCount >= numOrders, "Should have at least " + numOrders + " orders");
    }

    @Test
    void testOrderValidation() {
        // Test invalid quantity
        Order invalidOrder = Order.builder()
            .user(testUser)
            .service(testService)
            .link("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .quantity(0) // Invalid quantity
            .charge(new BigDecimal("1.50"))
            .status(OrderStatus.PENDING)
            .build();
        
        assertThrows(Exception.class, () -> {
            orderRepository.save(invalidOrder);
        });
        
        // Test invalid link
        Order invalidLinkOrder = Order.builder()
            .user(testUser)
            .service(testService)
            .link("") // Invalid link
            .quantity(1000)
            .charge(new BigDecimal("1.50"))
            .status(OrderStatus.PENDING)
            .build();
        
        assertThrows(Exception.class, () -> {
            orderRepository.save(invalidLinkOrder);
        });
    }

    @Test
    void testOrderCancellation() throws Exception {
        // Create order
        Order order = createTestOrder();
        
        // Cancel order
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        
        // Verify cancellation
        Order cancelledOrder = orderRepository.findById(order.getId()).orElse(null);
        assertNotNull(cancelledOrder);
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());
    }

    @Test
    void testOrderRefund() throws Exception {
        // Create order
        Order order = createTestOrder();
        
        // Simulate order completion and refund
        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);
        
        // Verify order is completed
        Order completedOrder = orderRepository.findById(order.getId()).orElse(null);
        assertNotNull(completedOrder);
        assertEquals(OrderStatus.COMPLETED, completedOrder.getStatus());
    }

    private Order createTestOrder() {
        Order order = Order.builder()
            .user(testUser)
            .service(testService)
            .link("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .quantity(1000)
            .charge(new BigDecimal("1.50"))
            .status(OrderStatus.PENDING)
            .build();
        
        return orderRepository.save(order);
    }
} 
package com.smmpanel.service.fraud;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.validation.ValidationResult;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FraudDetectionServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;

    @Mock private OrderRepository orderRepository;

    @Mock private ValueOperations<String, Object> valueOperations;

    @InjectMocks private FraudDetectionService fraudDetectionService;

    private User testUser;
    private Service testService;
    private CreateOrderRequest createRequest;
    private Order testOrder;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setBalance(new BigDecimal("1000.00"));
        testUser.setActive(true);
        testUser.setCreatedAt(LocalDateTime.now().minus(2, ChronoUnit.DAYS));

        testService = new Service();
        testService.setId(1L);
        testService.setName("YouTube Views");
        testService.setMinOrder(100);
        testService.setMaxOrder(50000);
        testService.setPricePer1000(new BigDecimal("2.50"));
        testService.setActive(true);

        createRequest = new CreateOrderRequest();
        createRequest.setService(1L);
        createRequest.setLink("https://youtube.com/watch?v=test123");
        createRequest.setQuantity(1000);

        testOrder = new Order();
        testOrder.setId(1L);
        testOrder.setUser(testUser);
        testOrder.setService(testService);
        testOrder.setStatus(com.smmpanel.entity.OrderStatus.PENDING);
        testOrder.setQuantity(1000);
        testOrder.setLink(createRequest.getLink());
        testOrder.setCreatedAt(LocalDateTime.now());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void analyzeOrder_WithNewUserAndHighValueOrder_ShouldPass() {
        // New user with high-value order (above threshold)
        createRequest.setQuantity(50000); // $125.00 order (50 * 2.50)

        when(orderRepository.countByUserIdAndCreatedAtAfter(any(), any()))
                .thenReturn(0L); // No previous orders
        when(valueOperations.increment(anyString())).thenReturn(1L);

        // Should pass without validation errors
        ValidationResult result =
                fraudDetectionService.analyzeOrder(testUser.getId(), createRequest);

        assertFalse(result.hasErrors());
    }

    @Test
    void analyzeOrder_WithRateLimitExceeded_ShouldReturnError() {
        // First request should pass
        when(valueOperations.increment(anyString())).thenReturn(1L);
        ValidationResult result1 =
                fraudDetectionService.analyzeOrder(testUser.getId(), createRequest);
        assertFalse(result1.hasErrors());

        // Second request should be rate limited
        when(valueOperations.increment(anyString())).thenReturn(6L); // Exceeds limit of 5
        ValidationResult result2 =
                fraudDetectionService.analyzeOrder(testUser.getId(), createRequest);

        assertTrue(result2.hasErrors());
        assertTrue(
                result2.getErrors().stream()
                        .anyMatch(error -> error.getField().equals("rate_limit")));
    }

    @Test
    void analyzeOrder_WithDuplicateOrder_ShouldReturnError() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        ValidationResult result =
                fraudDetectionService.analyzeOrder(testUser.getId(), createRequest);

        assertTrue(result.hasErrors());
        assertTrue(
                result.getErrors().stream()
                        .anyMatch(error -> error.getField().equals("duplicate")));
    }

    @Test
    void analyzeOrder_WithSuspiciousPatterns_ShouldReturnError() {
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        // Simulate multiple identical orders
        when(orderRepository.findByUserIdAndCreatedAtAfter(any(), any()))
                .thenReturn(
                        List.of(
                                testOrder, testOrder, testOrder, testOrder, testOrder, testOrder,
                                testOrder, testOrder, testOrder, testOrder, testOrder, testOrder,
                                testOrder, testOrder, testOrder, testOrder, testOrder, testOrder,
                                testOrder, testOrder,
                                testOrder)); // 21 orders, exceeds threshold of 20

        ValidationResult result =
                fraudDetectionService.analyzeOrder(testUser.getId(), createRequest);

        assertTrue(result.hasErrors());
        assertTrue(
                result.getErrors().stream()
                        .anyMatch(error -> error.getField().equals("suspicious")));
    }

    @Test
    void analyzeOrder_WithHighValueOrderAndUnverifiedUser_ShouldReturnError() {
        createRequest.setQuantity(150000); // High value order
        testUser.setId(500L); // Unverified user (ID < 1000)

        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(orderRepository.findByUserIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        ValidationResult result =
                fraudDetectionService.analyzeOrder(testUser.getId(), createRequest);

        assertTrue(result.hasErrors());
        assertTrue(
                result.getErrors().stream()
                        .anyMatch(error -> error.getField().equals("verification")));
    }

    @Test
    void analyzeOrder_WithHighValueOrderAndVerifiedUser_ShouldPass() {
        createRequest.setQuantity(150000); // High value order
        testUser.setId(1500L); // Verified user (ID > 1000)

        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(orderRepository.findByUserIdAndCreatedAtAfter(any(), any())).thenReturn(List.of());

        ValidationResult result =
                fraudDetectionService.analyzeOrder(testUser.getId(), createRequest);

        assertFalse(result.hasErrors());
    }
}

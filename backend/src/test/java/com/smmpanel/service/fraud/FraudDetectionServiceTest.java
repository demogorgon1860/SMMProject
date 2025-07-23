package com.smmpanel.service.fraud;

import com.smmpanel.config.fraud.FraudDetectionProperties;
import com.smmpanel.dto.OrderCreateRequest;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.ServiceEntity;
import com.smmpanel.entity.User;
import com.smmpanel.exception.FraudDetectionException;
import com.smmpanel.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    
    @Mock
    private OrderRepository orderRepository;
    
    @Mock
    private ValueOperations<String, String> valueOperations;
    
    @InjectMocks
    private FraudDetectionService fraudDetectionService;
    
    private FraudDetectionProperties properties;
    private User testUser;
    private ServiceEntity testService;
    private OrderCreateRequest createRequest;
    private Order testOrder;
    
    @BeforeEach
    void setUp() {
        properties = new FraudDetectionProperties();
        fraudDetectionService = new FraudDetectionService(redisTemplate, orderRepository, properties);
        
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setBalance(new BigDecimal("1000.00"));
        testUser.setActive(true);
        testUser.setCreatedAt(Date.from(Instant.now().minus(2, ChronoUnit.DAYS)));
        
        testService = new ServiceEntity();
        testService.setId(1L);
        testService.setName("YouTube Views");
        testService.setMinQuantity(100);
        testService.setMaxQuantity(50000);
        testService.setPricePerThousand(new BigDecimal("2.50"));
        testService.setActive(true);
        
        createRequest = new OrderCreateRequest();
        createRequest.setServiceId(1L);
        createRequest.setLink("https://youtube.com/watch?v=test123");
        createRequest.setQuantity(1000);
        
        testOrder = new Order();
        testOrder.setId(1L);
        testOrder.setUser(testUser);
        testOrder.setService(testService);
        testOrder.setStatus(com.smmpanel.entity.OrderStatus.PENDING);
        testOrder.setQuantity(1000);
        testOrder.setLink(createRequest.getLink());
        testOrder.setCreatedAt(new Date());
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }
    
    @Test
    void checkForFraud_WithNewUserAndHighValueOrder_ShouldPass() {
        // New user with high-value order (above threshold)
        testOrder.setQuantity(50000); // $125.00 order (50 * 2.50)
        
        when(orderRepository.countByUserAndCreatedAtAfter(any(), any()))
            .thenReturn(0L); // No previous orders
            
        // Should pass without exception
        assertDoesNotThrow(() -> 
            fraudDetectionService.checkForFraud(testUser, testOrder)
        );
    }
    
    @Test
    void checkForFraud_WithRateLimitExceeded_ShouldThrowException() {
        // Configure rate limiting to be very restrictive
        FraudDetectionProperties.RateLimit rateLimit = new FraudDetectionProperties.RateLimit();
        rateLimit.setRequestsPerMinute(1);
        rateLimit.setBucketCapacity(1);
        properties.setRateLimit(rateLimit);
        
        // First request should pass
        when(valueOperations.increment(anyString())).thenReturn(1L);
        assertDoesNotThrow(() -> fraudDetectionService.checkForFraud(testUser, testOrder));
        
        // Second request should be rate limited
        when(valueOperations.increment(anyString())).thenReturn(2L);
        assertThrows(FraudDetectionException.class, 
            () -> fraudDetectionService.checkForFraud(testUser, testOrder));
    }
    
    @Test
    void checkForFraud_WithDuplicateOrder_ShouldThrowException() {
        // Configure duplicate detection
        FraudDetectionProperties.DuplicateDetection dupDetection = new FraudDetectionProperties.DuplicateDetection();
        dupDetection.setEnabled(true);
        dupDetection.setTimeWindowMinutes(60);
        dupDetection.setMaxDuplicateAttempts(1);
        properties.setDuplicateDetection(dupDetection);
        
        // Simulate duplicate order
        Order duplicateOrder = new Order();
        duplicateOrder.setId(2L);
        duplicateOrder.setUser(testUser);
        duplicateOrder.setService(testService);
        duplicateOrder.setQuantity(1000);
        duplicateOrder.setLink("https://youtube.com/watch?v=test123");
        duplicateOrder.setCreatedAt(new Date());
        
        when(orderRepository.findRecentOrders(any(), any(), any(), any()))
            .thenReturn(List.of(testOrder));
        
        assertThrows(FraudDetectionException.class, 
            () -> fraudDetectionService.checkForFraud(testUser, duplicateOrder));
    }
    
    @Test
    void checkForFraud_WithSuspiciousPatterns_ShouldThrowException() {
        // Configure suspicious patterns
        FraudDetectionProperties.SuspiciousPatterns suspiciousPatterns = new FraudDetectionProperties.SuspiciousPatterns();
        suspiciousPatterns.setEnabled(true);
        suspiciousPatterns.setMaxOrdersPerHour(5);
        suspiciousPatterns.setMaxSameQuantityPercent(30);
        suspiciousPatterns.setHighValueThreshold(50.0);
        properties.setSuspiciousPatterns(suspiciousPatterns);
        
        // Simulate multiple identical orders
        when(orderRepository.countByUserAndCreatedAtAfter(any(), any()))
            .thenReturn(10L); // More than maxOrdersPerHour
            
        assertThrows(FraudDetectionException.class, 
            () -> fraudDetectionService.checkForFraud(testUser, testOrder));
    }
}

package com.smmpanel.service.order;

import com.smmpanel.dto.OrderCreateRequest;
import com.smmpanel.dto.response.OrderResponse;
import com.smmpanel.dto.UserPrincipal;
import com.smmpanel.dto.validation.ValidationError;
import com.smmpanel.dto.validation.ValidationResult;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.FraudDetectionException;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.OrderService;
import com.smmpanel.service.BalanceService;
import com.smmpanel.service.YouTubeAutomationService;
import com.smmpanel.service.validation.OrderValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderProcessingServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private OrderValidationService validationService;
    @Mock private BalanceService balanceService;
    @Mock private YouTubeAutomationService youTubeAutomationService;
    @Mock private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock private SecurityContext securityContext;
    @Mock private Authentication authentication;
    
    @InjectMocks private OrderService orderService;
    
    private Order testOrder;
    private User testUser;
    private Service testService;
    private OrderCreateRequest createRequest;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setBalance(new BigDecimal("1000.00"));
        testUser.setActive(true);
        testUser.setApiKey("test-api-key-123");
        
        testService = new Service();
        testService.setId(1L);
        testService.setName("YouTube Views");
        testService.setMinOrder(100);
        testService.setMaxOrder(50000);
        testService.setPricePer1000(new BigDecimal("2.50"));
        testService.setActive(true);
        
        createRequest = new OrderCreateRequest();
        createRequest.setServiceId(1L);
        createRequest.setLink("https://youtube.com/watch?v=test123");
        createRequest.setQuantity(1000);
        
        testOrder = new Order();
        testOrder.setId(1L);
        testOrder.setUser(testUser);
        testOrder.setService(testService);
        testOrder.setStatus(OrderStatus.PENDING);
        testOrder.setQuantity(1000);
        testOrder.setLink(createRequest.getLink());
        testOrder.setCharge(new BigDecimal("2.50")); // Set the charge for the order
        testOrder.setStartCount(0);
        testOrder.setRemains(1000);
        
        // Set up security context
        UserPrincipal userPrincipal = UserPrincipal.builder()
                .id(testUser.getId())
                .username(testUser.getUsername())
                .email(testUser.getEmail())
                .password(testUser.getPasswordHash())
                .roles(List.of(testUser.getRole().name()))
                .enabled(testUser.isActive())
                .build();
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userPrincipal);
        SecurityContextHolder.setContext(securityContext);
    }
    
    @Test
    void createOrder_WithValidRequest_ShouldCreateOrder() {
        // Mock the hashed API key lookup that OrderService actually uses
        String hashedApiKey = hashApiKey(testUser.getApiKey());
        when(userRepository.findByApiKeyHashAndIsActiveTrue(hashedApiKey)).thenReturn(Optional.of(testUser));
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(testUser));
        when(serviceRepository.findById(anyLong())).thenReturn(Optional.of(testService));
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(balanceService.checkAndDeductBalance(any(User.class), any(BigDecimal.class), any(Order.class), anyString())).thenReturn(true);
        
        OrderResponse result = orderService.createOrder(createRequest, testUser.getUsername());
        
        assertNotNull(result);
        verify(orderRepository).save(any(Order.class));
        verify(balanceService).checkAndDeductBalance(any(User.class), any(BigDecimal.class), any(Order.class), anyString());
        verify(kafkaTemplate).send(eq("smm.youtube.processing"), any(Long.class));
    }
    
    @Test
    void createOrder_WithValidationErrors_ShouldThrowException() {
        // Mock the hashed API key lookup
        String hashedApiKey = hashApiKey(testUser.getApiKey());
        when(userRepository.findByApiKeyHashAndIsActiveTrue(hashedApiKey)).thenReturn(Optional.of(testUser));
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.of(testUser));
        when(serviceRepository.findById(anyLong())).thenReturn(Optional.of(testService));
        
        // Set invalid quantity to trigger validation error
        createRequest.setQuantity(50); // Below minimum
        
        assertThrows(
            com.smmpanel.exception.OrderValidationException.class,
            () -> orderService.createOrder(createRequest, testUser.getUsername())
        );
        
        verify(orderRepository, never()).save(any(Order.class));
    }
    
    // Helper method to hash API key (matching OrderService implementation)
    private String hashApiKey(String apiKey) {
        if (apiKey == null) {
            throw new IllegalArgumentException("API key cannot be null");
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

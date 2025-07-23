package com.smmpanel.service.order;

import com.smmpanel.config.order.OrderProcessingProperties;
import com.smmpanel.dto.OrderCreateRequest;
import com.smmpanel.dto.OrderDto;
import com.smmpanel.dto.UserPrincipal;
import com.smmpanel.dto.validation.ValidationError;
import com.smmpanel.dto.validation.ValidationResult;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.ServiceEntity;
import com.smmpanel.entity.User;
import com.smmpanel.event.OrderCreatedEvent;
import com.smmpanel.exception.FraudDetectionException;
import com.smmpanel.repository.OrderRepository;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.fraud.FraudDetectionService;
import com.smmpanel.service.validation.OrderValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modelmapper.ModelMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderProcessingServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private OrderValidationService validationService;
    @Mock private FraudDetectionService fraudDetectionService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ModelMapper modelMapper;
    @Mock private SecurityContext securityContext;
    @Mock private Authentication authentication;
    
    @InjectMocks private OrderProcessingService orderProcessingService;
    
    private Order testOrder;
    private User testUser;
    private ServiceEntity testService;
    private OrderCreateRequest createRequest;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setUsername("testuser");
        testUser.setBalance(new BigDecimal("1000.00"));
        testUser.setActive(true);
        
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
        testOrder.setStatus(OrderStatus.PENDING);
        testOrder.setQuantity(1000);
        testOrder.setLink(createRequest.getLink());
        
        // Set up security context
        UserPrincipal userPrincipal = new UserPrincipal(testUser);
        when(securityContext.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(userPrincipal);
        SecurityContextHolder.setContext(securityContext);
    }
    
    @Test
    void createOrder_WithValidRequest_ShouldCreateOrder() {
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(serviceRepository.findById(anyLong())).thenReturn(Optional.of(testService));
        when(validationService.validateOrder(any(), any(), any())).thenReturn(ValidationResult.valid());
        when(orderRepository.save(any(Order.class))).thenReturn(testOrder);
        when(modelMapper.map(any(Order.class), eq(OrderDto.class))).thenReturn(new OrderDto());
        
        OrderDto result = orderProcessingService.createOrder(createRequest);
        
        assertNotNull(result);
        verify(orderRepository).save(any(Order.class));
        verify(eventPublisher).publishEvent(any(OrderCreatedEvent.class));
    }
    
    @Test
    void createOrder_WithValidationErrors_ShouldThrowException() {
        ValidationResult validationResult = new ValidationResult();
        validationResult.addError(new ValidationError("quantity", "Invalid quantity"));
        
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(testUser));
        when(serviceRepository.findById(anyLong())).thenReturn(Optional.of(testService));
        when(validationService.validateOrder(any(), any(), any())).thenReturn(validationResult);
        
        assertThrows(
            com.smmpanel.dto.validation.ValidationException.class,
            () -> orderProcessingService.createOrder(createRequest)
        );
        
        verify(orderRepository, never()).save(any(Order.class));
    }
}

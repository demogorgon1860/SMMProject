package com.smmpanel.service.validation;

import com.smmpanel.dto.request.CreateOrderRequest;
import com.smmpanel.dto.validation.ValidationError;
import com.smmpanel.dto.validation.ValidationResult;
import com.smmpanel.entity.User;
import com.smmpanel.entity.Service;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.YouTubeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderValidationService {
    
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final YouTubeService youTubeService;
    
    public ValidationResult validateOrder(Long userId, CreateOrderRequest request) {
        ValidationResult result = ValidationResult.builder().build();
        
        // 1. Validate user
        validateUser(userId, result);
        if (result.hasErrors()) {
            return result;
        }
        
        // 2. Validate service
        Service service = validateService(request.getService().intValue(), result);
        if (result.hasErrors() || service == null) {
            return result;
        }
        
        // 3. Validate quantity against service limits
        validateQuantity(request.getQuantity(), service, result);
        
        // 4. Validate YouTube video
        validateYouTubeVideo(request.getLink(), result);
        
        // 5. Check balance if all other validations pass
        if (!result.hasErrors()) {
            validateBalance(userId, request, service, result);
        }
        
        return result;
    }
    
    private void validateUser(Long userId, ValidationResult result) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            result.addError("user", "User not found");
            return;
        }
        
        User user = userOpt.get();
        if (!user.isActive()) {
            result.addError("user", "Account is suspended");
        }
    }
    
    private Service validateService(Integer serviceId, ValidationResult result) {
        if (serviceId == null) {
            result.addError("service", "Service ID is required");
            return null;
        }
        
        Optional<Service> serviceOpt = serviceRepository.findById(serviceId.longValue());
        if (serviceOpt.isEmpty()) {
            result.addError("service", "Service not found");
            return null;
        }
        
        Service service = serviceOpt.get();
        if (!service.getActive()) {
            result.addError("service", "Service is not active");
        }
        
        return service;
    }
    
    private void validateQuantity(Integer quantity, Service service, ValidationResult result) {
        if (quantity == null) {
            result.addError("quantity", "Quantity is required");
            return;
        }
        
        if (quantity < service.getMinOrder()) {
            result.addError("quantity", 
                String.format("Minimum order quantity is %d", service.getMinOrder()));
        }
        
        if (quantity > service.getMaxOrder()) {
            result.addError("quantity", 
                String.format("Maximum order quantity is %d", service.getMaxOrder()));
        }
    }
    
    private void validateYouTubeVideo(String videoUrl, ValidationResult result) {
        try {
            String videoId = youTubeService.extractVideoId(videoUrl);
            if (videoId == null || videoId.trim().isEmpty()) {
                result.addError("link", "Invalid YouTube URL format");
                return;
            }
            
            // Additional YouTube validation can be added here
            // For example, checking if video exists, is public, etc.
            
        } catch (Exception e) {
            log.error("Error validating YouTube video: {}", e.getMessage(), e);
            result.addError("link", "Unable to validate YouTube video: " + e.getMessage());
        }
    }
    
    private void validateBalance(Long userId, CreateOrderRequest request, Service service, ValidationResult result) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            result.addError("user", "User not found");
            return;
        }
        
        User user = userOpt.get();
        BigDecimal orderCost = calculateOrderCost(service, request.getQuantity());
        
        if (user.getBalance().compareTo(orderCost) < 0) {
            result.addError("balance", 
                String.format("Insufficient balance. Required: $%s, Available: $%s", 
                    orderCost, user.getBalance()));
        }
    }
    
    private BigDecimal calculateOrderCost(Service service, int quantity) {
        return service.getPricePer1000()
            .multiply(BigDecimal.valueOf(quantity))
            .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
    }
}

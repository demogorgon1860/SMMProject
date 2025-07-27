package com.smmpanel.dto.validation;

import com.smmpanel.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * Perfect Panel Request Validator
 * Validates all Perfect Panel API requests before processing
 */
@Slf4j
@Component
public class PerfectPanelRequestValidator {

    private static final Pattern URL_PATTERN = Pattern.compile(
        "^(https?://)?([\\da-z.-]+)\\.([a-z.]{2,6})[/\\w .-]*/?$"
    );

    private static final Pattern YOUTUBE_URL_PATTERN = Pattern.compile(
        "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?)/|.*[?&]v=)|youtu\\.be/)([^\"&?/\\s]{11})"
    );

    /**
     * Validate API key
     */
    public void validateApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ApiException("API key is required");
        }
        
        if (apiKey.length() < 10) {
            throw new ApiException("API key must be at least 10 characters long");
        }
    }

    /**
     * Validate action parameter
     */
    public void validateAction(String action) {
        if (!StringUtils.hasText(action)) {
            throw new ApiException("Action parameter is required");
        }
        
        String validActions = "add,status,services,balance,refill,cancel";
        if (!validActions.contains(action.toLowerCase())) {
            throw new ApiException("Invalid action. Valid actions are: " + validActions);
        }
    }

    /**
     * Validate add order parameters
     */
    public void validateAddOrderParams(Integer service, String link, Integer quantity) {
        if (service == null) {
            throw new ApiException("Service parameter is required");
        }
        
        if (service <= 0) {
            throw new ApiException("Service ID must be a positive number");
        }
        
        if (!StringUtils.hasText(link)) {
            throw new ApiException("Link parameter is required");
        }
        
        if (!isValidUrl(link)) {
            throw new ApiException("Invalid URL format");
        }
        
        if (quantity == null) {
            throw new ApiException("Quantity parameter is required");
        }
        
        if (quantity <= 0) {
            throw new ApiException("Quantity must be a positive number");
        }
        
        if (quantity > 1000000) {
            throw new ApiException("Quantity cannot exceed 1,000,000");
        }
    }

    /**
     * Validate order status parameters
     */
    public void validateOrderStatusParams(Long orderId) {
        if (orderId == null) {
            throw new ApiException("Order parameter is required");
        }
        
        if (orderId <= 0) {
            throw new ApiException("Order ID must be a positive number");
        }
    }

    /**
     * Validate multiple order status parameters
     */
    public void validateMultipleOrderStatusParams(String orderIds) {
        if (!StringUtils.hasText(orderIds)) {
            throw new ApiException("Orders parameter is required");
        }
        
        String[] ids = orderIds.split(",");
        if (ids.length > 100) {
            throw new ApiException("Cannot check more than 100 orders at once");
        }
        
        for (String id : ids) {
            try {
                Long orderId = Long.valueOf(id.trim());
                if (orderId <= 0) {
                    throw new ApiException("Order ID must be a positive number");
                }
            } catch (NumberFormatException e) {
                throw new ApiException("Invalid order ID format: " + id);
            }
        }
    }

    /**
     * Validate refill order parameters
     */
    public void validateRefillOrderParams(Long orderId) {
        validateOrderStatusParams(orderId);
    }

    /**
     * Validate cancel order parameters
     */
    public void validateCancelOrderParams(Long orderId) {
        validateOrderStatusParams(orderId);
    }

    /**
     * Validate YouTube URL format
     */
    public void validateYouTubeUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new ApiException("YouTube URL is required");
        }
        
        if (!YOUTUBE_URL_PATTERN.matcher(url).find()) {
            throw new ApiException("Invalid YouTube URL format");
        }
    }

    /**
     * Check if URL is valid
     */
    private boolean isValidUrl(String url) {
        return URL_PATTERN.matcher(url).matches();
    }

    /**
     * Validate service ID
     */
    public void validateServiceId(Integer serviceId) {
        if (serviceId == null) {
            throw new ApiException("Service ID is required");
        }
        
        if (serviceId <= 0) {
            throw new ApiException("Service ID must be a positive number");
        }
    }

    /**
     * Validate quantity for service
     */
    public void validateQuantity(Integer quantity, Integer minOrder, Integer maxOrder) {
        if (quantity == null) {
            throw new ApiException("Quantity is required");
        }
        
        if (quantity <= 0) {
            throw new ApiException("Quantity must be a positive number");
        }
        
        if (minOrder != null && quantity < minOrder) {
            throw new ApiException("Quantity must be at least " + minOrder);
        }
        
        if (maxOrder != null && quantity > maxOrder) {
            throw new ApiException("Quantity cannot exceed " + maxOrder);
        }
    }
} 
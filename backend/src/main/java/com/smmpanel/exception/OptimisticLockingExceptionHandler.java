package com.smmpanel.exception;

import com.smmpanel.dto.response.ApiResponse;
import com.smmpanel.service.OptimisticLockingService.OptimisticLockingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for optimistic locking failures
 * Provides consistent error responses for concurrent modification issues
 */
@RestControllerAdvice
@Slf4j
public class OptimisticLockingExceptionHandler {

    /**
     * Handle Spring's OptimisticLockingFailureException
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleOptimisticLockingFailure(
            OptimisticLockingFailureException ex, HttpServletRequest request) {
        
        log.warn("Optimistic locking failure in request: {} {}", 
                request.getMethod(), request.getRequestURI(), ex);
        
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("timestamp", LocalDateTime.now());
        errorDetails.put("path", request.getRequestURI());
        errorDetails.put("method", request.getMethod());
        errorDetails.put("errorType", "OPTIMISTIC_LOCKING_FAILURE");
        errorDetails.put("message", "The resource was modified by another transaction. Please refresh and try again.");
        errorDetails.put("suggestion", "Reload the data and retry your operation");
        
        // Check if we can extract entity information from the exception
        String exceptionMessage = ex.getMessage();
        if (exceptionMessage != null) {
            if (exceptionMessage.toLowerCase().contains("user")) {
                errorDetails.put("entityType", "User");
                errorDetails.put("specificMessage", "User data was modified by another operation");
            } else if (exceptionMessage.toLowerCase().contains("order")) {
                errorDetails.put("entityType", "Order");
                errorDetails.put("specificMessage", "Order was modified by another operation");
            } else if (exceptionMessage.toLowerCase().contains("transaction")) {
                errorDetails.put("entityType", "Transaction");
                errorDetails.put("specificMessage", "Transaction was modified by another operation");
            }
        }
        
        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .success(false)
                .message("Concurrent modification detected - please retry")
                .data(errorDetails)
                .build();
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handle custom OptimisticLockingException from our service
     */
    @ExceptionHandler(OptimisticLockingException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleCustomOptimisticLockingException(
            OptimisticLockingException ex, HttpServletRequest request) {
        
        log.error("Custom optimistic locking exception in request: {} {}", 
                request.getMethod(), request.getRequestURI(), ex);
        
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("timestamp", LocalDateTime.now());
        errorDetails.put("path", request.getRequestURI());
        errorDetails.put("method", request.getMethod());
        errorDetails.put("errorType", "OPTIMISTIC_LOCKING_RETRY_EXHAUSTED");
        errorDetails.put("message", "Failed to complete operation after multiple retry attempts");
        errorDetails.put("suggestion", "Please wait a moment and try again, or contact support if the issue persists");
        errorDetails.put("retryExhausted", true);
        
        ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                .success(false)
                .message("Operation failed due to concurrent modifications - retry limit exceeded")
                .data(errorDetails)
                .build();
        
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    /**
     * Handle general concurrent modification scenarios
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleConcurrentModification(
            Exception ex, HttpServletRequest request) {
        
        // Check if this is a concurrent modification exception
        String exceptionMessage = ex.getMessage();
        if (exceptionMessage != null && 
            (exceptionMessage.toLowerCase().contains("concurrent") ||
             exceptionMessage.toLowerCase().contains("version") ||
             exceptionMessage.toLowerCase().contains("optimistic"))) {
            
            log.warn("Potential concurrent modification issue in request: {} {}", 
                    request.getMethod(), request.getRequestURI(), ex);
            
            Map<String, Object> errorDetails = new HashMap<>();
            errorDetails.put("timestamp", LocalDateTime.now());
            errorDetails.put("path", request.getRequestURI());
            errorDetails.put("method", request.getMethod());
            errorDetails.put("errorType", "POTENTIAL_CONCURRENT_MODIFICATION");
            errorDetails.put("message", "Possible concurrent modification detected");
            errorDetails.put("suggestion", "Please refresh your data and try again");
            
            ApiResponse<Map<String, Object>> response = ApiResponse.<Map<String, Object>>builder()
                    .success(false)
                    .message("Possible concurrent modification - please retry")
                    .data(errorDetails)
                    .build();
            
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
        
        // If not a concurrent modification issue, let other handlers deal with it
        throw new RuntimeException(ex);
    }
}
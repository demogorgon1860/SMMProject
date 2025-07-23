package com.smmpanel.exception;

import com.smmpanel.dto.response.ErrorResponse;
import com.smmpanel.dto.response.PerfectPanelResponse;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.exceptions.BucketExecutionException;
import io.github.bucket4j.exceptions.BucketNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatus status, WebRequest request) {
        
        Map<String, String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() != null ? 
                                fieldError.getDefaultMessage() : "Validation failed"
                ));

        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                "Validation failed",
                status.value(),
                request.getContextPath(),
                errors
        );

        return handleExceptionInternal(ex, errorResponse, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpHeaders headers,
            HttpStatus status, WebRequest request) {
        
        String message = String.format("Method '%s' is not supported for this request. Supported methods: %s",
                ex.getMethod(), ex.getSupportedHttpMethods());
        
        ErrorResponse errorResponse = new ErrorResponse(
                LocalDateTime.now(),
                message,
                status.value(),
                request.getContextPath()
        );
        
        return handleExceptionInternal(ex, errorResponse, headers, status, request);
    }

    @ExceptionHandler(ServiceNotFoundException.class)
    public ResponseEntity<PerfectPanelResponse> handleServiceNotFound(ServiceNotFoundException ex) {
        log.error("Service not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(PerfectPanelResponse.error(ex.getMessage(), 404));
    }

    @ExceptionHandler({
        AccessDeniedException.class,
        org.springframework.security.access.AccessDeniedException.class
    })
    public ResponseEntity<PerfectPanelResponse> handleAccessDenied(Exception ex) {
        log.error("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(PerfectPanelResponse.error("Access Denied: You don't have permission to access this resource", 403));
    }

    @ExceptionHandler(BucketExecutionException.class)
    public ResponseEntity<PerfectPanelResponse> handleBucketExecutionException(BucketExecutionException ex) {
        log.error("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("X-Rate-Limit-Retry-After-Seconds", "60")
                .body(PerfectPanelResponse.error("Too many requests. Please try again later.", 429));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<PerfectPanelResponse> handleDataAccessException(DataAccessException ex) {
        log.error("Database access error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PerfectPanelResponse.error("A database error occurred. Please try again later.", 500));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<PerfectPanelResponse> handleConstraintViolation(ConstraintViolationException ex) {
        log.error("Database constraint violation: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PerfectPanelResponse.error("Invalid data: " + ex.getConstraintName(), 400));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<PerfectPanelResponse> handleValidationException(ValidationException ex) {
        log.error("Validation exception: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PerfectPanelResponse.error("Validation failed: " + ex.getMessage(), 400));
    }
    
    @ExceptionHandler(com.smmpanel.dto.validation.ValidationException.class)
    public ResponseEntity<PerfectPanelResponse> handleCustomValidationException(
            com.smmpanel.dto.validation.ValidationException ex) {
        log.error("Custom validation exception: {}", ex.getMessage());
        Map<String, Object> errors = new HashMap<>();
        errors.put("errors", ex.getErrors().stream()
            .map(error -> Map.of("field", error.getField(), "message", error.getMessage()))
            .collect(Collectors.toList()));
            
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(PerfectPanelResponse.error("Validation failed", 400, errors));
    }
    
    @ExceptionHandler(FraudDetectionException.class)
    public ResponseEntity<PerfectPanelResponse> handleFraudDetectionException(FraudDetectionException ex) {
        log.warn("Fraud detection triggered: {}", ex.getMessage());
        Map<String, Object> details = new HashMap<>();
        details.put("code", ex.getErrorCode());
        details.put("message", ex.getMessage());
        
        if (ex.getAdditionalDetails() != null) {
            details.putAll(ex.getAdditionalDetails());
        }
        
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(PerfectPanelResponse.error("Order rejected by fraud detection", 403, details));
    }
    
    @ExceptionHandler(OrderProcessingException.class)
    public ResponseEntity<PerfectPanelResponse> handleOrderProcessingException(OrderProcessingException ex) {
        log.error("Order processing error: {}", ex.getMessage(), ex);
        
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", ex.getOrderId());
        details.put("status", ex.getCurrentStatus());
        
        if (ex.getRetryable() != null) {
            details.put("retryable", ex.getRetryable());
        }
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PerfectPanelResponse.error("Order processing failed: " + ex.getMessage(), 500, details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PerfectPanelResponse> handleAllUncaughtException(Exception ex, WebRequest request) {
        log.error("Unhandled exception occurred: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PerfectPanelResponse.error("An unexpected error occurred. Please try again later.", 500));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<PerfectPanelResponse> handleUserNotFound(UserNotFoundException ex) {
        log.error("User not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(PerfectPanelResponse.error(ex.getMessage(), 404));
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<PerfectPanelResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        log.error("Insufficient balance: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(PerfectPanelResponse.error(ex.getMessage(), 402));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        log.error("Authentication failed: {}", ex.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.UNAUTHORIZED.value())
                .error("Unauthorized")
                .message("Invalid credentials")
                .path(request.getRequestURI())
                .build();
                
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.error("Access denied: {}", ex.getMessage());
        
        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("Forbidden")
                .message("Access denied")
                .path(request.getRequestURI())
                .build();
                
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        
        Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            validationErrors.put(fieldName, errorMessage);
        });

        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("Validation Failed")
                .message("Invalid input data")
                .path(request.getRequestURI())
                .validationErrors(validationErrors)
                .build();

        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PerfectPanelResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.error("Invalid argument: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(PerfectPanelResponse.error(ex.getMessage(), 400));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<PerfectPanelResponse> handleRuntimeException(RuntimeException ex) {
        log.error("Runtime exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PerfectPanelResponse.error("Internal server error", 500));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        
        ErrorResponse error = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("Internal Server Error")
                .message("An unexpected error occurred")
                .path(request.getRequestURI())
                .build();
                
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
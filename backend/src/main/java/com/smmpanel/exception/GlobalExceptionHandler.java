package com.smmpanel.exception;

import com.smmpanel.dto.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PRODUCTION-READY Global Exception Handler
 * 
 * IMPROVEMENTS:
 * 1. Comprehensive error mapping with user-friendly messages
 * 2. Proper error classification and HTTP status codes
 * 3. Security-aware error responses (no sensitive data exposure)
 * 4. Structured error format for API clients
 * 5. Request ID tracking for debugging
 * 6. Environment-specific error details
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @Value("${app.error.include-stack-trace:false}")
    private boolean includeStackTrace;
    
    @Value("${app.error.include-debug-info:false}")
    private boolean includeDebugInfo;

    /**
     * Handle validation errors from @Valid
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.BAD_REQUEST,
            "VALIDATION_ERROR",
            "Validation failed for one or more fields",
            request
        );
        errorResponse.setValidationErrors(errors);

        log.warn("Validation error: {}", errors);
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle constraint validation errors
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        for (ConstraintViolation<?> violation : violations) {
            String fieldName = violation.getPropertyPath().toString();
            String errorMessage = violation.getMessage();
            errors.put(fieldName, errorMessage);
        }

        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.BAD_REQUEST,
            "CONSTRAINT_VIOLATION",
            "Constraint validation failed",
            request
        );
        errorResponse.setValidationErrors(errors);

        log.warn("Constraint violation: {}", errors);
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle authentication errors
     */
    @ExceptionHandler({AuthenticationException.class, BadCredentialsException.class})
    public ResponseEntity<ErrorResponse> handleAuthenticationError(AuthenticationException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.UNAUTHORIZED,
            "AUTHENTICATION_ERROR", 
            "Authentication failed",
            request
        );

        log.warn("Authentication error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    /**
     * Handle authorization errors
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.FORBIDDEN,
            "ACCESS_DENIED",
            "Access denied",
            request
        );

        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Handle business logic exceptions
     */
    @ExceptionHandler(UserValidationException.class)
    public ResponseEntity<ErrorResponse> handleUserValidation(UserValidationException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.BAD_REQUEST,
            "USER_VALIDATION_ERROR",
            ex.getMessage(),
            request
        );

        log.warn("User validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    @ExceptionHandler(OrderValidationException.class)
    public ResponseEntity<ErrorResponse> handleOrderValidation(OrderValidationException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.BAD_REQUEST,
            "ORDER_VALIDATION_ERROR",
            ex.getMessage(),
            request
        );

        log.warn("Order validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.PAYMENT_REQUIRED,
            "INSUFFICIENT_BALANCE",
            "Insufficient balance to complete this operation",
            request
        );

        log.warn("Insufficient balance: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(errorResponse);
    }

    /**
     * Handle external service exceptions
     */
    @ExceptionHandler(BinomApiException.class)
    public ResponseEntity<ErrorResponse> handleBinomApiError(BinomApiException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.SERVICE_UNAVAILABLE,
            "EXTERNAL_SERVICE_ERROR",
            "External service temporarily unavailable. Please try again later.",
            request
        );

        log.error("Binom API error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    @ExceptionHandler(VideoProcessingException.class)
    public ResponseEntity<ErrorResponse> handleVideoProcessingError(VideoProcessingException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "VIDEO_PROCESSING_ERROR",
            "Video processing failed. Please check your video URL and try again.",
            request
        );

        log.error("Video processing error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorResponse);
    }

    /**
     * Handle state transition errors
     */
    @ExceptionHandler(IllegalOrderStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateTransition(IllegalOrderStateTransitionException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.CONFLICT,
            "ILLEGAL_STATE_TRANSITION",
            ex.getMessage(),
            request
        );

        log.warn("Illegal state transition: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handle HTTP method not supported
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.METHOD_NOT_ALLOWED,
            "METHOD_NOT_ALLOWED",
            "HTTP method not supported for this endpoint",
            request
        );

        log.warn("Method not allowed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorResponse);
    }

    /**
     * Handle missing request parameters
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.BAD_REQUEST,
            "MISSING_PARAMETER",
            "Missing required parameter: " + ex.getParameterName(),
            request
        );

        log.warn("Missing parameter: {}", ex.getParameterName());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle type mismatch errors
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.BAD_REQUEST,
            "TYPE_MISMATCH",
            "Invalid parameter type for: " + ex.getName(),
            request
        );

        log.warn("Type mismatch for parameter {}: {}", ex.getName(), ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle malformed JSON
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.BAD_REQUEST,
            "MALFORMED_JSON",
            "Invalid JSON format in request body",
            request
        );

        log.warn("Malformed JSON: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    /**
     * Handle rate limiting errors
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.TOO_MANY_REQUESTS,
            "RATE_LIMIT_EXCEEDED",
            "Rate limit exceeded. Please try again later.",
            request
        );

        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse);
    }

    /**
     * Handle all other exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        ErrorResponse errorResponse = createErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred. Please try again later.",
            request
        );

        // Log with full stack trace for debugging
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    /**
     * Create standardized error response
     */
    private ErrorResponse createErrorResponse(HttpStatus status, String errorCode, String message, WebRequest request) {
        String requestId = UUID.randomUUID().toString();
        String path = extractPath(request);
        
        ErrorResponse errorResponse = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .errorCode(errorCode)
                .message(message)
                .path(path)
                .requestId(requestId)
                .build();

        // Add debug information in development
        if (includeDebugInfo) {
            Map<String, Object> debugInfo = new HashMap<>();
            debugInfo.put("userAgent", request.getHeader("User-Agent"));
            debugInfo.put("remoteAddr", request.getRemoteAddr());
            errorResponse.setDebugInfo(debugInfo);
        }

        return errorResponse;
    }

    private String extractPath(WebRequest request) {
        try {
            return request.getDescription(false).replace("uri=", "");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
package com.smmpanel.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request Validation Interceptor
 * Implements request security validations:
 * - Request ID tracking
 * - Input validation
 * - Request size limits
 * - Security headers validation
 */
public class RequestValidationInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestValidationInterceptor.class);
    private static final int MAX_URL_LENGTH = 2000;
    private static final int MAX_PAYLOAD_SIZE = 10 * 1024 * 1024; // 10MB

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Generate and set request ID
        String requestId = UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);

        // Validate URL length
        if (request.getRequestURL().length() > MAX_URL_LENGTH) {
            response.sendError(HttpStatus.URI_TOO_LONG.value(), 
                "URL exceeds maximum length of " + MAX_URL_LENGTH);
            return false;
        }

        // Validate content length
        String contentLength = request.getHeader("Content-Length");
        if (contentLength != null) {
            long size = Long.parseLong(contentLength);
            if (size > MAX_PAYLOAD_SIZE) {
                response.sendError(HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "Request payload exceeds maximum size of " + MAX_PAYLOAD_SIZE + " bytes");
                return false;
            }
        }

        // Validate content type
        String contentType = request.getContentType();
        if (contentType != null && !isValidContentType(contentType)) {
            response.sendError(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                "Unsupported content type: " + contentType);
            return false;
        }

        // Validate security headers
        if (!validateSecurityHeaders(request, response)) {
            return false;
        }

        // Log request metadata
        log.debug("Processing request: {} {} (ID: {})", 
            request.getMethod(), 
            request.getRequestURI(),
            requestId);

        return true;
    }

    private boolean isValidContentType(String contentType) {
        return contentType.startsWith("application/json") ||
               contentType.startsWith("multipart/form-data") ||
               contentType.startsWith("application/x-www-form-urlencoded");
    }

    private boolean validateSecurityHeaders(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // Validate Origin header for CORS requests
        String origin = request.getHeader("Origin");
        if (origin != null && !isValidOrigin(origin)) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Invalid Origin");
            return false;
        }

        // Check for required security headers in production
        if (isProdEnvironment()) {
            if (request.getHeader("X-Real-IP") == null && 
                request.getHeader("X-Forwarded-For") == null) {
                response.sendError(HttpStatus.BAD_REQUEST.value(), 
                    "Missing required proxy headers");
                return false;
            }
        }

        return true;
    }

    private boolean isValidOrigin(String origin) {
        // Implement origin validation logic based on allowed domains
        // This should match CORS configuration
        return true; // Placeholder - implement actual validation
    }

    private boolean isProdEnvironment() {
        String env = System.getProperty("spring.profiles.active");
        return "prod".equalsIgnoreCase(env);
    }
}

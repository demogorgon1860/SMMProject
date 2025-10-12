package com.smmpanel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * API Endpoint Standards Configuration Based on Stack Overflow best practices for REST API design
 *
 * <p>Key Principles: 1. Use plural forms for collections (orders, users, services) 2. Use
 * kebab-case for multi-word resources (balance-transactions) 3. Version via URL path (/api/v1,
 * /api/v2) 4. Use @PreAuthorize for method-level security 5. Apply consistent naming patterns
 */
@Configuration
@EnableMethodSecurity(
        prePostEnabled = true, // Enable @PreAuthorize and @PostAuthorize
        securedEnabled = true, // Enable @Secured (legacy support)
        jsr250Enabled = true // Enable @RolesAllowed
        )
public class ApiEndpointStandards implements WebMvcConfigurer {

    /**
     * Standard REST endpoint patterns following Stack Overflow best practices:
     *
     * <p>PLURAL RESOURCES (Collections): - GET /api/v1/orders -> List all orders - POST
     * /api/v1/orders -> Create new order - GET /api/v1/orders/{id} -> Get specific order - PUT
     * /api/v1/orders/{id} -> Update entire order - PATCH /api/v1/orders/{id} -> Partial update -
     * DELETE /api/v1/orders/{id} -> Delete order
     *
     * <p>SUB-RESOURCES: - GET /api/v1/users/{userId}/orders -> User's orders - POST
     * /api/v1/orders/{id}/cancel -> Action on resource
     *
     * <p>SEARCH/FILTER: - GET /api/v1/orders?status=active&limit=10 - GET
     * /api/v1/orders/search?q=keyword
     *
     * <p>SINGULAR RESOURCES (Singletons): - GET /api/v1/profile -> Current user profile - PUT
     * /api/v1/profile -> Update profile - POST /api/v1/auth/login -> Login action - POST
     * /api/v1/auth/logout -> Logout action
     *
     * <p>STATISTICS/AGGREGATES: - GET /api/v1/orders/stats -> Order statistics - GET
     * /api/v1/orders/count -> Total count
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // Note: Spring Boot 3.x no longer supports trailing slash matching
        // The default behavior is to not match trailing slashes

        // Set URL path helper for consistent URL parsing
        configurer.setUrlPathHelper(
                new org.springframework.web.util.UrlPathHelper() {
                    {
                        setUrlDecode(true);
                        setRemoveSemicolonContent(false);
                    }
                });
    }

    /** Endpoint naming conventions to follow: */
    public static class EndpointConventions {

        // Resource naming (use plural for collections)
        public static final String ORDERS = "orders";
        public static final String USERS = "users";
        public static final String SERVICES = "services";
        public static final String PAYMENTS = "payments";
        public static final String TRANSACTIONS = "transactions";
        public static final String NOTIFICATIONS = "notifications";

        // Singleton resources (use singular)
        public static final String PROFILE = "profile";
        public static final String HEALTH = "health";
        public static final String CONFIG = "config";
        public static final String AUTH = "auth";

        // Standard actions (POST endpoints)
        public static final String CANCEL = "cancel";
        public static final String APPROVE = "approve";
        public static final String REJECT = "reject";
        public static final String RETRY = "retry";
        public static final String REFRESH = "refresh";
        public static final String VERIFY = "verify";

        // Query endpoints
        public static final String SEARCH = "search";
        public static final String STATS = "stats";
        public static final String COUNT = "count";
        public static final String EXPORT = "export";

        // API versioning
        public static final String API_V1 = "/api/v1";
        public static final String API_V2 = "/api/v2";
        public static final String API_ADMIN = "/api/admin";
        public static final String API_INTERNAL = "/api/internal";
    }

    /** Security patterns based on Stack Overflow best practices */
    public static class SecurityPatterns {

        // Public endpoints (no authentication required)
        public static final String[] PUBLIC_PATTERNS = {
            "/api/v*/auth/login",
            "/api/v*/auth/register",
            "/api/v*/auth/refresh",
            "/api/v*/health/**",
            "/api/public/**"
        };

        // User endpoints (requires USER role)
        public static final String[] USER_PATTERNS = {
            "/api/v*/orders/**", "/api/v*/profile/**", "/api/v*/payments/**"
        };

        // Admin endpoints (requires ADMIN role)
        public static final String[] ADMIN_PATTERNS = {
            "/api/admin/**", "/api/v*/admin/**", "/api/v*/monitoring/**"
        };

        // Internal endpoints (system-to-system only)
        public static final String[] INTERNAL_PATTERNS = {"/api/internal/**", "/actuator/**"};
    }

    /**
     * HTTP method usage guidelines:
     *
     * <p>GET - Retrieve resource(s), should be idempotent POST - Create new resource or perform
     * action PUT - Full update/replace of existing resource PATCH - Partial update of existing
     * resource DELETE - Remove resource
     *
     * <p>Status codes to use: 200 OK - Successful GET, PUT, PATCH, DELETE 201 Created - Successful
     * POST creating resource 202 Accepted - Request accepted for async processing 204 No Content -
     * Successful DELETE with no body 400 Bad Request - Client error in request 401 Unauthorized -
     * Authentication required 403 Forbidden - Authenticated but not authorized 404 Not Found -
     * Resource doesn't exist 409 Conflict - Resource conflict (duplicate, etc) 422 Unprocessable -
     * Validation errors 429 Too Many Req - Rate limit exceeded 500 Internal Error - Server error
     * 503 Service Unavail - Temporary unavailability
     */
}

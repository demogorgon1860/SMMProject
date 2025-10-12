package com.smmpanel.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * API Versioning Interceptor Implements version handling based on Stack Overflow best practices
 *
 * <p>Supports: 1. URL path versioning (/api/v1, /api/v2) 2. Header-based versioning (X-API-Version)
 * 3. Accept header versioning (application/vnd.smmpanel.v1+json)
 */
@Slf4j
@Component
public class ApiVersioningInterceptor implements HandlerInterceptor {

    @Value("${api.default-version:v1}")
    private String defaultVersion;

    @Value("${api.min-supported-version:v1}")
    private String minSupportedVersion;

    @Value("${api.max-supported-version:v1}")
    private String maxSupportedVersion;

    @Value("${api.deprecation-warning-version:}")
    private String deprecationWarningVersion;

    private static final String API_VERSION_HEADER = "X-API-Version";
    private static final String API_DEPRECATION_HEADER = "X-API-Deprecation-Warning";
    private static final String API_MIN_VERSION_HEADER = "X-API-Min-Version";
    private static final String API_MAX_VERSION_HEADER = "X-API-Max-Version";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String requestPath = request.getRequestURI();
        String method = request.getMethod();

        // Extract version from different sources
        String version = extractVersion(request);

        // Log API version being used
        log.debug("API Request: {} {} - Version: {}", method, requestPath, version);

        // Add version info to response headers
        response.setHeader(API_MIN_VERSION_HEADER, minSupportedVersion);
        response.setHeader(API_MAX_VERSION_HEADER, maxSupportedVersion);

        // Check if version is deprecated
        if (deprecationWarningVersion != null && deprecationWarningVersion.equals(version)) {
            String warningMessage =
                    String.format(
                            "API version %s is deprecated and will be removed in future releases. "
                                    + "Please upgrade to %s",
                            version, maxSupportedVersion);
            response.setHeader(API_DEPRECATION_HEADER, warningMessage);
            response.setHeader("Warning", "299 - \"" + warningMessage + "\"");
            log.warn("Deprecated API version used: {} for {}", version, requestPath);
        }

        // Check if version is supported
        if (!isVersionSupported(version)) {
            log.error("Unsupported API version: {} for {}", version, requestPath);
            response.setStatus(HttpServletResponse.SC_GONE); // 410 Gone
            response.getWriter()
                    .write(
                            String.format(
                                    "{\"error\":\"API version %s is no longer supported. "
                                            + "Supported versions: %s to %s\"}",
                                    version, minSupportedVersion, maxSupportedVersion));
            return false;
        }

        // Store version in request attribute for potential use in controllers
        request.setAttribute("api.version", version);

        return true;
    }

    @Override
    public void postHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            ModelAndView modelAndView)
            throws Exception {
        // Add performance metrics header
        Long startTime = (Long) request.getAttribute("startTime");
        if (startTime != null) {
            long executionTime = System.currentTimeMillis() - startTime;
            response.setHeader("X-Response-Time", executionTime + "ms");
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex)
            throws Exception {
        if (ex != null) {
            log.error("Request failed: {} {}", request.getMethod(), request.getRequestURI(), ex);
        }
    }

    /**
     * Extract API version from request Priority order (Stack Overflow best practice): 1. URL path
     * (/api/v1/...) 2. Custom header (X-API-Version) 3. Accept header
     * (application/vnd.smmpanel.v1+json) 4. Default version
     */
    private String extractVersion(HttpServletRequest request) {
        String path = request.getRequestURI();

        // 1. Check URL path
        if (path.matches(".*/api/v\\d+/.*")) {
            return path.replaceAll(".*/api/(v\\d+)/.*", "$1");
        }

        // 2. Check custom header
        String headerVersion = request.getHeader(API_VERSION_HEADER);
        if (headerVersion != null && !headerVersion.isEmpty()) {
            return normalizeVersion(headerVersion);
        }

        // 3. Check Accept header for content negotiation
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader != null && acceptHeader.contains("vnd.smmpanel")) {
            // Extract version from: application/vnd.smmpanel.v1+json
            if (acceptHeader.matches(".*vnd\\.smmpanel\\.v\\d+.*")) {
                return acceptHeader.replaceAll(".*vnd\\.smmpanel\\.(v\\d+).*", "$1");
            }
        }

        // 4. Return default version
        return defaultVersion;
    }

    /** Normalize version string (handle v1, V1, 1, 1.0 formats) */
    private String normalizeVersion(String version) {
        if (version == null) return defaultVersion;

        version = version.trim().toLowerCase();

        // Handle numeric versions (1, 1.0) -> v1
        if (version.matches("\\d+(\\.\\d+)?")) {
            return "v" + version.split("\\.")[0];
        }

        // Handle prefixed versions (v1, V1) -> v1
        if (version.matches("v\\d+")) {
            return version;
        }

        return defaultVersion;
    }

    /** Check if version is within supported range */
    private boolean isVersionSupported(String version) {
        if (version == null) return false;

        try {
            int versionNum = Integer.parseInt(version.substring(1));
            int minVersion = Integer.parseInt(minSupportedVersion.substring(1));
            int maxVersion = Integer.parseInt(maxSupportedVersion.substring(1));

            return versionNum >= minVersion && versionNum <= maxVersion;
        } catch (Exception e) {
            log.error("Error parsing version: {}", version, e);
            return false;
        }
    }
}

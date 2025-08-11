package com.smmpanel.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/** API Version Interceptor Validates API version headers and handles version compatibility */
public class ApiVersionInterceptor implements HandlerInterceptor {

    private static final String VERSION_HEADER = "X-Api-Version";
    private static final String CURRENT_API_VERSION = "1.0";
    private static final String MINIMUM_SUPPORTED_VERSION = "1.0";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String apiVersion = request.getHeader(VERSION_HEADER);

        // Add current version to response header
        response.addHeader(VERSION_HEADER, CURRENT_API_VERSION);

        // Skip version check for whitelisted paths
        String path = request.getRequestURI();
        if (path.startsWith("/api/public/") || path.startsWith("/api/docs/")) {
            return true;
        }

        // Require API version header for all other API calls
        if (apiVersion == null || apiVersion.isEmpty()) {
            response.sendError(
                    HttpStatus.BAD_REQUEST.value(), "Missing required header: " + VERSION_HEADER);
            return false;
        }

        // Validate version compatibility
        if (!isVersionSupported(apiVersion)) {
            response.sendError(
                    HttpStatus.BAD_REQUEST.value(),
                    "Unsupported API version. Minimum supported version: "
                            + MINIMUM_SUPPORTED_VERSION);
            return false;
        }

        return true;
    }

    private boolean isVersionSupported(String version) {
        try {
            float clientVersion = Float.parseFloat(version);
            float minimumVersion = Float.parseFloat(MINIMUM_SUPPORTED_VERSION);
            return clientVersion >= minimumVersion;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

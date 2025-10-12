package com.smmpanel.security.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
// @Configuration  // TEMPORARILY DISABLED to fix UnsupportedOperationException
public class XssProtectionConfig {

    private static final Pattern[] XSS_PATTERNS = {
        // Script tags
        Pattern.compile("<script>(.*?)</script>", Pattern.CASE_INSENSITIVE),
        Pattern.compile(
                "src[\r\n]*=[\r\n]*\\\'(.*?)\\\'",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile(
                "src[\r\n]*=[\r\n]*\\\"(.*?)\\\"",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),

        // HTML events
        Pattern.compile(
                "onload(.*?)=", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile(
                "onerror(.*?)=", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile(
                "onclick(.*?)=", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),

        // CSS with JavaScript
        Pattern.compile(
                "expression\\((.*?)\\)",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),

        // Other injection attempts
        Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile(
                "eval\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL)
    };

    @Bean
    public OncePerRequestFilter xssFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain)
                    throws ServletException, IOException {

                // Add security headers
                response.setHeader("X-XSS-Protection", "1; mode=block");
                response.setHeader("X-Content-Type-Options", "nosniff");

                // Check if this is a JSON API request
                String contentType = request.getContentType();
                String requestUri = request.getRequestURI();

                // Skip XSS filtering for:
                // 1. JSON content type requests
                // 2. API endpoints that expect JSON
                boolean isJsonRequest =
                        (contentType != null
                                && contentType.toLowerCase().contains("application/json"));
                boolean isApiEndpoint = (requestUri != null && requestUri.startsWith("/api/"));

                if (isJsonRequest || isApiEndpoint) {
                    // Don't apply XSS filtering to JSON API requests
                    log.debug("Skipping XSS filter for JSON/API request: {}", requestUri);
                    filterChain.doFilter(request, response);
                } else {
                    // Apply XSS filtering only for non-JSON requests (forms, HTML, etc.)
                    log.debug("Applying XSS filter for non-JSON request: {}", requestUri);
                    XssRequestWrapper wrappedRequest = new XssRequestWrapper(request, XSS_PATTERNS);
                    filterChain.doFilter(wrappedRequest, response);
                }
            }
        };
    }
}

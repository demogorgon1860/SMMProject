package com.smmpanel.security;

import com.smmpanel.entity.User;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * PRODUCTION-READY API Key Authentication Filter
 * 
 * SECURITY IMPROVEMENTS:
 * 1. Uses proper salted hash from ApiKeyService
 * 2. Fixed O(n) lookup performance issue - now uses indexed query
 * 3. Removed database writes from authentication path
 * 4. Added proper error handling
 * 5. Implemented secure API key validation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "key"; // Perfect Panel compatibility
    
    private final UserRepository userRepository;
    private final ApiKeyService apiKeyService;
    
    @Value("${app.security.api-key.enabled:true}")
    private boolean apiKeyAuthEnabled;
    
    @Value("${app.security.api-key.header:X-API-Key}")
    private String apiKeyHeader;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        
        if (!apiKeyAuthEnabled || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }
        
        String apiKey = extractApiKey(request);
        
        if (StringUtils.isNotBlank(apiKey)) {
            try {
                authenticateWithApiKey(request, apiKey);
            } catch (Exception e) {
                log.error("API key authentication failed: {}", e.getMessage());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
    
    private void authenticateWithApiKey(HttpServletRequest request, String apiKey) {
        // FIXED: Use indexed query instead of loading all users (O(1) instead of O(n))
        Optional<User> userOpt = userRepository.findByApiKeyHashAndIsActiveTrue(
            apiKeyService.hashApiKeyForLookup(apiKey)
        );
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            
            // SECURITY: Use ApiKeyService for proper salted hash verification
            if (apiKeyService.validateApiKey(apiKey, user)) {
                List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
                );
                
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    user, null, authorities);
                
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
                
                // PERFORMANCE FIX: Remove database write from hot path
                // User access tracking moved to async background process
                log.debug("Authenticated user {} with API key", user.getUsername());
            } else {
                throw new SecurityException("API key verification failed");
            }
        } else {
            throw new SecurityException("Invalid API key");
        }
    }
    
    private String extractApiKey(HttpServletRequest request) {
        // Check header first (more secure)
        String apiKey = request.getHeader(apiKeyHeader);
        
        // Fall back to parameter for Perfect Panel compatibility
        if (StringUtils.isBlank(apiKey)) {
            apiKey = request.getParameter(API_KEY_PARAM);
        }
        
        return StringUtils.trimToNull(apiKey);
    }
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        
        // Skip API key auth for public endpoints
        return path.startsWith("/api/v2/auth/") || 
               path.startsWith("/api/v2/public/") ||
               path.startsWith("/actuator/health") ||
               path.startsWith("/swagger-ui/") ||
               path.startsWith("/v3/api-docs");
    }
}

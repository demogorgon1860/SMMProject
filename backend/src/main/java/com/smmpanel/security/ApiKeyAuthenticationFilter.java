package com.smmpanel.security;

import com.smmpanel.entity.User;
import com.smmpanel.repository.UserRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PRODUCTION-READY API Key Authentication Filter
 * 
 * SECURITY IMPROVEMENTS:
 * 1. Fixed O(n) lookup performance issue - now uses indexed query
 * 2. Removed database writes from authentication path
 * 3. Added proper error handling
 * 4. Implemented secure API key hashing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "api_key";
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
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
        String apiKeyHash = hashApiKey(apiKey);
        Optional<User> userOpt = userRepository.findByApiKeyHashAndIsActiveTrue(apiKeyHash);
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            
            // SECURITY: Verify API key matches exactly
            if (verifyApiKey(apiKey, user)) {
                List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(user.getRole().getAuthority())
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
        
        // Fall back to parameter for backward compatibility
        if (StringUtils.isBlank(apiKey)) {
            apiKey = request.getParameter(API_KEY_PARAM);
        }
        
        return StringUtils.trimToNull(apiKey);
    }
    
    /**
     * SECURITY: Hash API key for secure storage and comparison
     */
    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * SECURITY: Verify API key using constant-time comparison
     */
    private boolean verifyApiKey(String providedApiKey, User user) {
        if (user.getApiKey() == null) {
            return false;
        }
        
        try {
            String providedHash = hashApiKey(providedApiKey);
            String storedHash = user.getApiKeyHash();
            
            // Use MessageDigest.isEqual for constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                providedHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Error verifying API key for user {}: {}", user.getUsername(), e.getMessage());
            return false;
        }
    }
    
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        
        // Skip API key auth for public endpoints
        return path.startsWith("/api/auth/") || 
               path.startsWith("/api/public/") ||
               path.startsWith("/actuator/health") ||
               path.startsWith("/swagger-ui/") ||
               path.startsWith("/v3/api-docs");
    }
}

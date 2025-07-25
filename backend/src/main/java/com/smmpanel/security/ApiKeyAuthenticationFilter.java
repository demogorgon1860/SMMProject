package com.smmpanel.security;

import com.smmpanel.entity.User;
import com.smmpanel.exception.ApiException;
import com.smmpanel.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
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
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_PARAM = "api_key";
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
    @Value("${app.security.api-key.rate-limit:100}")
    private int apiKeyRateLimit;
    
    @Value("${app.security.api-key.rate-limit-window:3600000}")
    private long rateLimitWindowMs;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String apiKey = extractApiKey(request);
        
        if (StringUtils.isNotBlank(apiKey) && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                // Rate limiting check would go here
                // checkRateLimit(apiKey, request);
                
                // Find user by API key (secure hash-based lookup)
                Optional<User> userOpt = findUserByApiKey(apiKey);
                
                if (userOpt.isEmpty()) {
                    log.warn("API key not found: {}", maskApiKey(apiKey));
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API key");
                    return;
                }
                
                User user = userOpt.get();
                
                if (!user.getIsActive()) {
                    log.warn("Inactive user attempted access: {}", user.getUsername());
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "User account is not active");
                    return;
                }
                
                // Update last API access time
                user.setLastApiAccess(LocalDateTime.now());
                userRepository.save(user);
                
                // Create authentication token with user details and authorities
                List<SimpleGrantedAuthority> authorities = List.of(
                    new SimpleGrantedAuthority(user.getRole().getAuthority())
                );
                
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    user, null, authorities);
                
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
                
                log.debug("Authenticated user {} with API key", user.getUsername());
                
            } catch (Exception e) {
                log.error("API key authentication failed: {}", e.getMessage());
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Authentication error");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
    
    private String extractApiKey(HttpServletRequest request) {
        // Check header first
        String apiKey = request.getHeader(API_KEY_HEADER);
        
        // Fall back to parameter (for backward compatibility)
        if (StringUtils.isBlank(apiKey)) {
            apiKey = request.getParameter(API_KEY_PARAM);
        }
        
        return StringUtils.trimToNull(apiKey);
    }
    
    private Optional<User> findUserByApiKey(String apiKey) {
        try {
            // Hash the API key to search for it securely
            // Note: We need to search through all users and verify the hash
            // This is a temporary solution - in production, consider using a lookup table
            List<User> users = userRepository.findAll();
            
            for (User user : users) {
                if (verifyApiKey(apiKey, user)) {
                    return Optional.of(user);
                }
            }
            
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error searching for user by API key: {}", e.getMessage());
            return Optional.empty();
        }
    }
    
    private boolean verifyApiKey(String apiKey, User user) {
        // If using hashed API keys
        if (StringUtils.isNotBlank(user.getApiKeyHash()) && StringUtils.isNotBlank(user.getApiKeySalt())) {
            try {
                String hashedInput = hashApiKey(apiKey, user.getApiKeySalt());
                return MessageDigest.isEqual(hashedInput.getBytes(StandardCharsets.UTF_8), 
                                          user.getApiKeyHash().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("Error verifying API key hash: {}", e.getMessage());
                return false;
            }
        }
        
        // Fallback to direct comparison (for backward compatibility)
        return StringUtils.equals(apiKey, user.getApiKey());
    }
    
    private String hashApiKey(String apiKey, String salt) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        digest.update(salt.getBytes(StandardCharsets.UTF_8));
        byte[] hashedBytes = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hashedBytes);
    }
    
    private String maskApiKey(String apiKey) {
        if (StringUtils.isBlank(apiKey) || apiKey.length() < 8) {
            return "[hidden]";
        }
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}

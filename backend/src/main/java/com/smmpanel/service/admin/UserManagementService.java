package com.smmpanel.service.admin;

import com.smmpanel.dto.admin.UserDto;
import com.smmpanel.dto.admin.UserCreationRequest;
import com.smmpanel.dto.admin.UserUpdateRequest;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.repository.UserRepository;
// import com.smmpanel.service.AuditService; // TODO: Re-enable when AuditService is implemented
import com.smmpanel.exception.UserValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DECOMPOSED: User Management Service
 * 
 * ARCHITECTURAL IMPROVEMENTS:
 * 1. Single Responsibility - Only handles user management
 * 2. Clear separation of concerns
 * 3. Proper validation and error handling
 * 4. Audit logging for all operations
 * 5. Performance optimizations
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserManagementService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // private final AuditService auditService; // TODO: Re-enable when AuditService is implemented

    /**
     * Get paginated users with optional filters
     */
    public Page<UserDto> getUsers(UserRole role, Boolean active, BigDecimal minBalance, Pageable pageable) {
        Page<User> users = userRepository.findUsersWithFilters(role, active, minBalance, pageable);
        return users.map(this::mapToUserDto);
    }

    /**
     * Get user by ID
     */
    public UserDto getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserValidationException("User not found: " + userId));
        
        return mapToUserDto(user);
    }

    /**
     * Create new user with validation
     */
    @Transactional
    public UserDto createUser(UserCreationRequest request) {
        validateUserCreation(request);
        
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setBalance(request.getInitialBalance() != null ? request.getInitialBalance() : BigDecimal.ZERO);
        user.setTimezone(request.getTimezone() != null ? request.getTimezone() : "UTC");
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        
        // Generate API key if requested
        if (request.getGenerateApiKey()) {
            generateApiKey(user);
        }
        
        user = userRepository.save(user);
        
        auditService.logUserCreation(user);
        log.info("Created user: {} with role: {}", user.getUsername(), user.getRole());
        
        return mapToUserDto(user);
    }

    /**
     * Update existing user
     */
    @Transactional
    public UserDto updateUser(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserValidationException("User not found: " + userId));
        
        validateUserUpdate(user, request);
        
        // Update fields if provided
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        
        if (request.getRole() != null) {
            UserRole oldRole = user.getRole();
            user.setRole(request.getRole());
            auditService.logRoleChange(user, oldRole, request.getRole());
        }
        
        if (request.getTimezone() != null) {
            user.setTimezone(request.getTimezone());
        }
        
        if (request.getActive() != null) {
            boolean oldActive = user.isActive();
            user.setActive(request.getActive());
            if (oldActive != request.getActive()) {
                auditService.logUserStatusChange(user, oldActive, request.getActive());
            }
        }
        
        user.setUpdatedAt(LocalDateTime.now());
        user = userRepository.save(user);
        
        log.info("Updated user: {}", user.getUsername());
        return mapToUserDto(user);
    }

    /**
     * Delete user (soft delete by deactivation)
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserValidationException("User not found: " + userId));
        
        if (user.getRole() == UserRole.ADMIN) {
            long adminCount = userRepository.countByRoleAndIsActiveTrue(UserRole.ADMIN);
            if (adminCount <= 1) {
                throw new UserValidationException("Cannot delete the last admin user");
            }
        }
        
        user.setActive(false);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        
        auditService.logUserDeletion(user);
        log.info("Deleted (deactivated) user: {}", user.getUsername());
    }

    /**
     * Generate new API key for user
     */
    @Transactional
    public String generateApiKey(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserValidationException("User not found: " + userId));
        
        generateApiKey(user);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        
        auditService.logApiKeyGeneration(user);
        log.info("Generated new API key for user: {}", user.getUsername());
        
        return user.getApiKey();
    }

    /**
     * Revoke API key for user
     */
    @Transactional
    public void revokeApiKey(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserValidationException("User not found: " + userId));
        
        user.setApiKey(null);
        user.setApiKeyHash(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        
        auditService.logApiKeyRevocation(user);
        log.info("Revoked API key for user: {}", user.getUsername());
    }

    /**
     * Search users
     */
    public Page<UserDto> searchUsers(String search, Pageable pageable) {
        Page<User> users = userRepository.searchActiveUsers(search, pageable);
        return users.map(this::mapToUserDto);
    }

    // Private helper methods

    private void validateUserCreation(UserCreationRequest request) {
        if (userRepository.findByUsername(request.getUsername()).isPresent()) {
            throw new UserValidationException("Username already exists: " + request.getUsername());
        }
        
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new UserValidationException("Email already exists: " + request.getEmail());
        }
        
        if (request.getInitialBalance() != null && request.getInitialBalance().compareTo(BigDecimal.ZERO) < 0) {
            throw new UserValidationException("Initial balance cannot be negative");
        }
    }

    private void validateUserUpdate(User user, UserUpdateRequest request) {
        if (request.getEmail() != null && 
            userRepository.existsByEmailAndIdNot(request.getEmail(), user.getId())) {
            throw new UserValidationException("Email already exists: " + request.getEmail());
        }
        
        // Prevent last admin from being demoted or deactivated
        if (user.getRole() == UserRole.ADMIN && 
            (request.getRole() != UserRole.ADMIN || Boolean.FALSE.equals(request.getActive()))) {
            
            long adminCount = userRepository.countByRoleAndIsActiveTrue(UserRole.ADMIN);
            if (adminCount <= 1) {
                throw new UserValidationException("Cannot modify the last admin user");
            }
        }
    }

    private void generateApiKey(User user) {
        String apiKey = UUID.randomUUID().toString().replace("-", "");
        user.setApiKey(apiKey);
        user.setApiKeyHash(hashApiKey(apiKey));
    }

    private String hashApiKey(String apiKey) {
        // Use the same hashing logic as in ApiKeyAuthenticationFilter
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private UserDto mapToUserDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .balance(user.getBalance())
                .timezone(user.getTimezone())
                .hasApiKey(user.getApiKey() != null)
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .lastLoginAt(user.getLastLoginAt())
                .build();
    }
} 
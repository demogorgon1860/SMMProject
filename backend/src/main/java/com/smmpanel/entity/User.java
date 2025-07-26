package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * OPTIMIZED User Entity with security and performance improvements
 * 
 * IMPROVEMENTS:
 * 1. Added proper database indexes for performance
 * 2. Separated API key and its hash for security
 * 3. Implemented UserDetails for Spring Security integration
 * 4. Added optimistic locking for concurrent balance updates
 * 5. Lazy loading relationships to prevent N+1 queries
 * 6. Added audit fields and soft delete capability
 */
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_username", columnList = "username"),
    @Index(name = "idx_users_email", columnList = "email"),
    @Index(name = "idx_users_api_key_hash", columnList = "api_key_hash"),
    @Index(name = "idx_users_active", columnList = "is_active"),
    @Index(name = "idx_users_role", columnList = "role"),
    @Index(name = "idx_users_balance", columnList = "balance"),
    @Index(name = "idx_users_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"passwordHash", "apiKey", "apiKeyHash"}) // Exclude sensitive fields from toString
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /**
     * SECURITY IMPROVEMENT: Store raw API key temporarily for response
     * This is only set when generating new keys and should be nulled after response
     */
    @Transient
    private String apiKey;

    /**
     * SECURITY IMPROVEMENT: Store hashed API key for lookup
     * This is what's actually stored in database and used for authentication
     */
    @Column(name = "api_key_hash", unique = true, length = 64)
    private String apiKeyHash;

    @Column(name = "balance", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.USER;

    @Column(name = "timezone", length = 50)
    @Builder.Default
    private String timezone = "UTC";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "email_verified", nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(name = "two_factor_enabled", nullable = false)
    @Builder.Default
    private boolean twoFactorEnabled = false;

    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_api_access_at")
    private LocalDateTime lastApiAccessAt;

    @Column(name = "api_key_salt", length = 64)
    private String apiKeySalt;

    @Column(name = "api_key_last_rotated")
    private LocalDateTime apiKeyLastRotated;

    @Column(name = "preferred_currency", length = 3)
    @Builder.Default
    private String preferredCurrency = "USD";

    @Column(name = "total_spent", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    /**
     * PERFORMANCE IMPROVEMENT: Optimistic locking for balance updates
     * Prevents concurrent modification issues during balance transactions
     */
    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * PERFORMANCE IMPROVEMENT: Lazy loading relationships to prevent N+1 queries
     * Orders will only be loaded when explicitly accessed
     */
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<Order> orders;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<BalanceTransaction> balanceTransactions;

    // Spring Security UserDetails implementation

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return isActive;
    }

    @Override
    public boolean isAccountNonLocked() {
        return lockedUntil == null || lockedUntil.isBefore(LocalDateTime.now());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // Consider credentials expired if password is older than 365 days
        if (passwordChangedAt == null) {
            return true; // New accounts or accounts without password change tracking
        }
        return passwordChangedAt.isAfter(LocalDateTime.now().minusDays(365));
    }

    @Override
    public boolean isEnabled() {
        return isActive && emailVerified;
    }

    // Business logic methods

    /**
     * Check if user has sufficient balance for a transaction
     */
    public boolean hasSufficientBalance(BigDecimal amount) {
        return balance.compareTo(amount) >= 0;
    }

    /**
     * Check if user account is locked due to failed login attempts
     */
    public boolean isTemporarilyLocked() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }

    /**
     * Check if user has administrative privileges
     */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /**
     * Check if user has operator privileges or higher
     */
    public boolean isOperatorOrHigher() {
        return role == UserRole.OPERATOR || role == UserRole.ADMIN;
    }

    /**
     * Update last login time and reset failed attempts
     */
    public void recordSuccessfulLogin() {
        this.lastLoginAt = LocalDateTime.now();
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /**
     * Record failed login attempt and potentially lock account
     */
    public void recordFailedLogin() {
        this.failedLoginAttempts++;
        
        // Lock account after 5 failed attempts for 30 minutes
        if (this.failedLoginAttempts >= 5) {
            this.lockedUntil = LocalDateTime.now().plusMinutes(30);
        }
    }

    /**
     * Update API access time (called asynchronously to avoid performance impact)
     */
    public void recordApiAccess() {
        this.lastApiAccessAt = LocalDateTime.now();
    }

    // Helper methods for balance operations (should be used within transactions)

    /**
     * Add amount to balance (use within transaction)
     */
    public void addBalance(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Cannot add negative amount");
        }
        this.balance = this.balance.add(amount);
    }

    /**
     * Subtract amount from balance (use within transaction)
     */
    public void subtractBalance(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Cannot subtract negative amount");
        }
        if (!hasSufficientBalance(amount)) {
            throw new IllegalArgumentException("Insufficient balance");
        }
        this.balance = this.balance.subtract(amount);
    }

    /**
     * Set new balance (use within transaction)
     */
    public void setBalance(BigDecimal newBalance) {
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
        this.balance = newBalance;
    }

    // Validation methods

    /**
     * Validate user data before persistence
     */
    @PrePersist
    @PreUpdate
    private void validateUser() {
        if (username != null) {
            username = username.trim().toLowerCase();
        }
        if (email != null) {
            email = email.trim().toLowerCase();
        }
        if (balance == null) {
            balance = BigDecimal.ZERO;
        }
        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
    }
}

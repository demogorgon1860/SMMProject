package com.smmpanel.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Data
@Entity
@Table(name = "users")
@EqualsAndHashCode(callSuper = false)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @JsonIgnore
    @Column(name = "api_key_hash", length = 256)
    private String apiKeyHash;

    @JsonIgnore
    @Column(name = "api_key_salt", length = 128)
    private String apiKeySalt;

    @Column(name = "api_key_last_rotated")
    private LocalDateTime apiKeyLastRotated;

    @Column(name = "last_api_access")
    private LocalDateTime lastApiAccess;

    @Column(precision = 18, scale = 8)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "total_spent", precision = 18, scale = 8)
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @Column(name = "preferred_currency", length = 3, nullable = false, columnDefinition = "VARCHAR(3) DEFAULT 'USD'")
    private String preferredCurrency = "USD";

    @Enumerated(EnumType.STRING)
    private UserRole role = UserRole.USER;

    private String timezone = "UTC";

    @Column(name = "is_active")
    private Boolean isActive = true;
    
    @Transient
    @JsonIgnore
    private transient String apiKey; // Transient field for API key (not persisted)

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    /**
     * Gets the masked API key for display purposes
     * @return Masked API key (first 4 and last 4 characters)
     */
    @JsonIgnore
    public String getMaskedApiKey() {
        if (apiKey != null && apiKey.length() > 8) {
            return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
        }
        return "[hidden]";
    }
    
    /**
     * Checks if the user has an active API key
     * @return true if the user has an API key, false otherwise
     */
    @JsonIgnore
    public boolean hasApiKey() {
        return apiKeyHash != null && apiKeySalt != null;
    }
}

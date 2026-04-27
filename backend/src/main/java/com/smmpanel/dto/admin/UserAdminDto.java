package com.smmpanel.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Slim, admin-safe projection of {@link com.smmpanel.entity.User} for the {@code /v2/admin/users}
 * listing. The User entity itself was being serialized whole, which leaked {@code passwordHash},
 * {@code apiKeyHash}, {@code apiKeySalt}, and the bcrypt password into the admin response. This
 * DTO exposes only fields the operator UI legitimately needs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserAdminDto {
    private Long id;
    private String username;
    private String email;
    private BigDecimal balance;
    private BigDecimal totalSpent;
    private String role;
    /** Lower-case "active" or "suspended" — what the UI status badge renders directly. */
    private String status;
    private boolean emailVerified;
    private boolean twoFactorEnabled;
    /** True if the user has rotated/created an API key (we never expose the key/hash itself). */
    private boolean apiKeyConfigured;
    private long ordersCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}

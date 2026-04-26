package com.smmpanel.dto.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Full profile shape returned by GET /v1/me. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProfileMeResponse {
    private Long id;
    private String username;
    private String email;
    private String role;
    private BigDecimal balance;
    private boolean active;
    private boolean emailVerified;
    private LocalDateTime emailVerifiedAt;
    private boolean twoFactorEnabled;
    private String timezone;
    private boolean apiKeyConfigured;
    private LocalDateTime apiKeyLastRotated;
    private LocalDateTime createdAt;
    private LocalDateTime lastLoginAt;
}

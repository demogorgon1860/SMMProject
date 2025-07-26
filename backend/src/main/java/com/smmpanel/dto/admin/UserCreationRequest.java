package com.smmpanel.dto.admin;

import com.smmpanel.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreationRequest {
    private String username;
    private String email;
    private String password;
    private UserRole role;
    private BigDecimal initialBalance;
    private String timezone;
    private boolean generateApiKey;
} 
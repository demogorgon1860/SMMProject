package com.smmpanel.dto;

import com.smmpanel.entity.UserRole;
import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;

@Data
@Builder
public class UserUpdateRequest {
    private String email;
    private UserRole role;
    private String timezone;
    private BigDecimal balance;
    private Boolean isActive;
} 
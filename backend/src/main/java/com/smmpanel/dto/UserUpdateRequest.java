package com.smmpanel.dto;

import com.smmpanel.entity.UserRole;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserUpdateRequest {
    private String email;
    private UserRole role;
    private String timezone;
    private BigDecimal balance;
    private Boolean isActive;
}

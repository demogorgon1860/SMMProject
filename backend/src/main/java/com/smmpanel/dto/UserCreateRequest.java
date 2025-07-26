package com.smmpanel.dto;

import com.smmpanel.entity.UserRole;
import lombok.Data;
import lombok.Builder;

@Data
@Builder
public class UserCreateRequest {
    private String username;
    private String email;
    private String password;
    private UserRole role;
    private String timezone;
} 
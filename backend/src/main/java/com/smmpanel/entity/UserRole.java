package com.smmpanel.entity;

import org.springframework.security.core.GrantedAuthority;

/**
 * User role enumeration with Spring Security integration
 */
public enum UserRole implements GrantedAuthority {
    USER("ROLE_USER", "Regular user"),
    OPERATOR("ROLE_OPERATOR", "Panel operator"),
    ADMIN("ROLE_ADMIN", "System administrator");
    
    private final String authority;
    private final String description;
    
    UserRole(String authority, String description) {
        this.authority = authority;
        this.description = description;
    }
    
    @Override
    public String getAuthority() {
        return authority;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Get role name without ROLE_ prefix
     */
    public String getRoleName() {
        return this.name();
    }
    
    /**
     * Check if this role has admin privileges
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
    
    /**
     * Check if this role has operator privileges or higher
     */
    public boolean isOperatorOrHigher() {
        return this == OPERATOR || this == ADMIN;
    }
}
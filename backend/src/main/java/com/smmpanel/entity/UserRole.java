package com.smmpanel.entity;

public enum UserRole {
    USER("ROLE_USER"),
    OPERATOR("ROLE_OPERATOR"),
    ADMIN("ROLE_ADMIN");
    
    private final String authority;
    
    UserRole(String authority) {
        this.authority = authority;
    }
    
    public String getAuthority() {
        return authority;
    }
}
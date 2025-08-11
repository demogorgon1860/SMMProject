package com.smmpanel.entity;

public enum YouTubeAccountStatus {
    ACTIVE("Active and working"),
    BLOCKED("Blocked by YouTube"),
    SUSPENDED("Temporarily suspended"),
    RATE_LIMITED("Rate limited"),
    INACTIVE("Manually deactivated");

    private final String description;

    YouTubeAccountStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

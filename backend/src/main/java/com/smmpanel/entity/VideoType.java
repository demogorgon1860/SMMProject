package com.smmpanel.entity;

public enum VideoType {
    REGULAR("Regular Video"),
    SHORT("YouTube Short"),
    LIVE("Live Stream"),
    PREMIERE("Premiere"),
    UNKNOWN("Unknown");
    
    private final String displayName;
    
    VideoType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
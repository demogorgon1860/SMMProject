package com.smmpanel.entity;

public enum VideoType {
    STANDARD("Standard Video"),
    SHORTS("YouTube Shorts"),
    LIVE("Live Stream");
    
    private final String description;
    
    VideoType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
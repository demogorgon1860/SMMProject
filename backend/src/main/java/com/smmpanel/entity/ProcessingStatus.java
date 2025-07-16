package com.smmpanel.entity;

public enum ProcessingStatus {
    PENDING("Pending processing"),
    PROCESSING("Currently processing"),
    COMPLETED("Processing completed"),
    FAILED("Processing failed"),
    RETRY("Waiting for retry");
    
    private final String description;
    
    ProcessingStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
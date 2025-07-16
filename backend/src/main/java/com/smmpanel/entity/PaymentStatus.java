package com.smmpanel.entity;

public enum PaymentStatus {
    PENDING("Waiting for payment"),
    PROCESSING("Processing payment"),
    COMPLETED("Payment completed"),
    FAILED("Payment failed"),
    EXPIRED("Payment expired");
    
    private final String description;
    
    PaymentStatus(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}
package com.smmpanel.entity;

public enum TransactionType {
    DEPOSIT("Balance deposit"),
    ORDER_PAYMENT("Order payment"),
    REFUND("Order refund"),
    REFILL("Order refill"),
    BONUS("Bonus credit"),
    ADJUSTMENT("Manual adjustment");
    
    private final String description;
    
    TransactionType(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
}

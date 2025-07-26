package com.smmpanel.entity;

public enum TransactionType {
    DEPOSIT,        // Balance deposit
    ORDER_PAYMENT,  // Payment for order
    REFUND,         // Refund to user
    REFILL          // Refill compensation
}

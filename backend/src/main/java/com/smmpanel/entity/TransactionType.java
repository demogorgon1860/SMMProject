package com.smmpanel.entity;

public enum TransactionType {
    DEPOSIT,        // Balance deposit
    ORDER_PAYMENT,  // Payment for order
    REFUND,         // Refund to user
    REFILL,         // Refill compensation
    TRANSFER_IN,    // Incoming balance transfer
    TRANSFER_OUT,   // Outgoing balance transfer
    ADJUSTMENT,     // Administrative balance adjustment
    BONUS,          // Promotional bonus
    COMMISSION,     // Affiliate commission
    PENALTY         // Administrative penalty
}

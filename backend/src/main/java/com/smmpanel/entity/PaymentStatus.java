package com.smmpanel.entity;

public enum PaymentStatus {
    PENDING,      // Payment initiated
    PROCESSING,   // Payment being processed
    COMPLETED,    // Payment successful
    FAILED,       // Payment failed
    EXPIRED       // Payment expired
}
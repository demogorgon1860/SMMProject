package com.smmpanel.entity;

/**
 * CRITICAL: Perfect Panel compatible order statuses
 * MUST match exactly for API compatibility
 */
public enum OrderStatus {
    PENDING,      // Order created, awaiting processing
    IN_PROGRESS,  // Processing started
    PROCESSING,   // YouTube clip creation in progress
    ACTIVE,       // Order running, views being delivered
    PARTIAL,      // Partially completed
    COMPLETED,    // Fully completed
    CANCELLED,    // Cancelled by user/admin
    PAUSED,       // Temporarily paused
    HOLDING,      // On hold due to error/issue
    REFILL        // Refill order
}
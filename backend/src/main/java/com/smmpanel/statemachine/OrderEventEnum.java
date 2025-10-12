package com.smmpanel.statemachine;

/**
 * Order Event Enumeration for State Machine Transitions Based on Stack Overflow best practices for
 * order workflow management Reference:
 * https://stackoverflow.com/questions/54975168/spring-state-machine-configuration
 */
public enum OrderEventEnum {
    // Order Creation Events
    ORDER_PLACED, // Initial order placement
    PAYMENT_CONFIRMED, // Payment has been verified
    PAYMENT_FAILED, // Payment verification failed

    // Processing Events
    START_PROCESSING, // Begin order processing
    PROCESSING_STARTED, // Processing has begun
    PROCESSING_COMPLETED, // Processing finished successfully
    PROCESSING_FAILED, // Processing encountered error

    // Fulfillment Events
    ACTIVATE_ORDER, // Activate the order
    PAUSE_ORDER, // Temporarily pause order
    RESUME_ORDER, // Resume paused order

    // Completion Events
    MARK_PARTIAL, // Partial completion
    MARK_COMPLETED, // Full completion

    // Cancellation Events
    CANCEL_REQUESTED, // User requests cancellation
    CANCEL_APPROVED, // Cancellation approved
    REFUND_INITIATED, // Refund process started
    REFUND_COMPLETED, // Refund completed

    // Error Recovery Events
    RETRY_PROCESSING, // Retry failed processing
    MANUAL_INTERVENTION, // Requires manual intervention
    ERROR_RESOLVED, // Error has been resolved

    // Admin Events
    ADMIN_OVERRIDE, // Admin manual override
    ADMIN_SUSPEND, // Admin suspends order
    ADMIN_REACTIVATE // Admin reactivates order
}

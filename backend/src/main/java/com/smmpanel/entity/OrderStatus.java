package com.smmpanel.entity;

public enum OrderStatus {
    PENDING,
    IN_PROGRESS,
    PROCESSING,
    ACTIVE,
    PARTIAL,
    COMPLETED,
    CANCELLED,
    PAUSED,
    HOLDING,
    REFILL,
    ERROR,
    SUSPENDED;

    /**
     * "Terminal" = the order's outcome is already booked (delivery + refund settled). Once a
     * panel-side action (force_complete, mark_partial, cancel) has put the order in one of these
     * states, late bot updates over RabbitMQ / HTTP must NOT overwrite the status — that's how we
     * used to lose force-complete to a stray bot progress message that flipped the row back to
     * PROCESSING.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == CANCELLED;
    }
}

package com.smmpanel.entity;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
    REFUNDED;

    /**
     * "Settled" = the deposit's outcome has already been booked. {@link #COMPLETED} means the
     * wallet was credited; {@link #REFUNDED} means a credit was reversed by admin. After either, a
     * late Cryptomus webhook with status fail/cancel/expired must NOT downgrade the row — doing so
     * would leave the user with the credit but the audit trail showing FAILED, which is exactly the
     * kind of inconsistency that bites at month 6 during a chargeback dispute.
     *
     * <p>Note: PENDING → FAILED → COMPLETED is still allowed (legitimate recovery scenario when the
     * user pays late after Cryptomus initially timed out). The asymmetry is intentional —
     * downgrading from COMPLETED is always a bug, upgrading to COMPLETED is sometimes valid.
     */
    public boolean isCreditedOrReversed() {
        return this == COMPLETED || this == REFUNDED;
    }
}

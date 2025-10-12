package com.smmpanel.dto.result;

/** Error recovery actions */
public enum ErrorRecoveryAction {
    RETRY_SCHEDULED,
    DEAD_LETTER_QUEUE,
    MANUAL_RETRY,
    ERROR,
    SKIP,
    ESCALATE
}

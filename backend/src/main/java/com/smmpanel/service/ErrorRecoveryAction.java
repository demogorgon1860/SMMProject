package com.smmpanel.service;

/** Error recovery actions */
public enum ErrorRecoveryAction {
    RETRY_SCHEDULED,
    DEAD_LETTER_QUEUE,
    MANUAL_RETRY,
    ERROR,
    SKIP,
    ESCALATE
}

package com.smmpanel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when an illegal order state transition is attempted
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class IllegalOrderStateTransitionException extends RuntimeException {

    public IllegalOrderStateTransitionException(String message) {
        super(message);
    }

    public IllegalOrderStateTransitionException(String message, Throwable cause) {
        super(message, cause);
    }

    public IllegalOrderStateTransitionException(String fromState, String toState) {
        super(String.format("Illegal state transition from %s to %s", fromState, toState));
    }

    public IllegalOrderStateTransitionException(String fromState, String toState, String reason) {
        super(String.format("Illegal state transition from %s to %s: %s", fromState, toState, reason));
    }
} 
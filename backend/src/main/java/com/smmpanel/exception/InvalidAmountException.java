package com.smmpanel.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;

/**
 * Exception thrown when an invalid amount is provided for balance operations
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidAmountException extends RuntimeException {

    public InvalidAmountException(String message) {
        super(message);
    }

    public InvalidAmountException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidAmountException(BigDecimal amount) {
        super(String.format("Invalid amount: %s", amount));
    }

    public InvalidAmountException(BigDecimal amount, String operation) {
        super(String.format("Invalid amount %s for operation: %s", amount, operation));
    }
} 
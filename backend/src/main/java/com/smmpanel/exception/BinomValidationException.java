package com.smmpanel.exception;

/** Exception thrown when Binom API request validation fails */
public class BinomValidationException extends RuntimeException {

    public BinomValidationException(String message) {
        super(message);
    }

    public BinomValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

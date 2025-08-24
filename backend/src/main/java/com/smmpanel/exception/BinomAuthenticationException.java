package com.smmpanel.exception;

/** Exception thrown when authentication with Binom API fails */
public class BinomAuthenticationException extends RuntimeException {

    public BinomAuthenticationException(String message) {
        super(message);
    }

    public BinomAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}

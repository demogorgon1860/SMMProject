package com.smmpanel.exception;

/** Exception thrown for temporary Binom API failures that should be retried */
public class BinomTemporaryException extends RuntimeException {

    public BinomTemporaryException(String message) {
        super(message);
    }

    public BinomTemporaryException(String message, Throwable cause) {
        super(message, cause);
    }
}

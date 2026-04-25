package com.smmpanel.exception;

public class OrderQuotaExceededException extends RuntimeException {
    public OrderQuotaExceededException(String message) {
        super(message);
    }

    public OrderQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.smmpanel.exception;

public class BinomApiException extends RuntimeException {
    public BinomApiException(String message) {
        super(message);
    }
    
    public BinomApiException(String message, Throwable cause) {
        super(message, cause);
    }
}

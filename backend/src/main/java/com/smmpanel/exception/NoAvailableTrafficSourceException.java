package com.smmpanel.exception;

public class NoAvailableTrafficSourceException extends RuntimeException {
    public NoAvailableTrafficSourceException(String message) {
        super(message);
    }
    
    public NoAvailableTrafficSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}

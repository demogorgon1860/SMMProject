package com.smmpanel.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Generic API exception for application-level errors
 */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public ApiException(String message) {
        super(message);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.errorCode = "API_ERROR";
    }

    public ApiException(String message, HttpStatus status) {
        super(message);
        this.status = status;
        this.errorCode = "API_ERROR";
    }

    public ApiException(String message, HttpStatus status, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = HttpStatus.INTERNAL_SERVER_ERROR;
        this.errorCode = "API_ERROR";
    }

    public ApiException(String message, Throwable cause, HttpStatus status) {
        super(message, cause);
        this.status = status;
        this.errorCode = "API_ERROR";
    }

    public ApiException(String message, Throwable cause, HttpStatus status, String errorCode) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }
} 
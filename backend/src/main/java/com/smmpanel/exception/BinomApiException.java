package com.smmpanel.exception;

import org.springframework.http.HttpStatus;

/** Enhanced Binom API Exception with HTTP status code support */
public class BinomApiException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String binomErrorCode;
    private final String endpoint;

    public BinomApiException(String message) {
        super(message);
        this.httpStatus = null;
        this.binomErrorCode = null;
        this.endpoint = null;
    }

    public BinomApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = null;
        this.binomErrorCode = null;
        this.endpoint = null;
    }

    public BinomApiException(String message, HttpStatus httpStatus, String endpoint) {
        super(message);
        this.httpStatus = httpStatus;
        this.binomErrorCode = null;
        this.endpoint = endpoint;
    }

    public BinomApiException(
            String message, HttpStatus httpStatus, String binomErrorCode, String endpoint) {
        super(message);
        this.httpStatus = httpStatus;
        this.binomErrorCode = binomErrorCode;
        this.endpoint = endpoint;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getBinomErrorCode() {
        return binomErrorCode;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public boolean isClientError() {
        return httpStatus != null && httpStatus.is4xxClientError();
    }

    public boolean isServerError() {
        return httpStatus != null && httpStatus.is5xxServerError();
    }

    public boolean isRetryable() {
        if (httpStatus == null) return true;

        // Don't retry client errors (4xx) except for specific cases
        if (httpStatus.is4xxClientError()) {
            return httpStatus == HttpStatus.TOO_MANY_REQUESTS
                    || httpStatus == HttpStatus.REQUEST_TIMEOUT;
        }

        // Retry server errors (5xx)
        return httpStatus.is5xxServerError();
    }
}

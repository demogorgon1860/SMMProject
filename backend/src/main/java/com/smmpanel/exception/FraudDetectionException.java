package com.smmpanel.exception;

import java.util.Map;
import lombok.Getter;

@Getter
public class FraudDetectionException extends RuntimeException {
    private final String errorCode;
    private final Map<String, Object> additionalDetails;

    public FraudDetectionException(String message, String errorCode) {
        this(message, errorCode, null, null);
    }

    public FraudDetectionException(String message, String errorCode, Throwable cause) {
        this(message, errorCode, null, cause);
    }

    public FraudDetectionException(
            String message, String errorCode, Map<String, Object> additionalDetails) {
        this(message, errorCode, additionalDetails, null);
    }

    public FraudDetectionException(
            String message,
            String errorCode,
            Map<String, Object> additionalDetails,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.additionalDetails = additionalDetails;
    }
}

package com.smmpanel.service;

import lombok.Builder;
import lombok.Data;

/** Manual retry operation result */
@Builder
@Data
public class ManualRetryResult {
    private final Long orderId;
    private final boolean success;
    private final String errorMessage;
    private final String operatorNotes;
    private final boolean retryCountReset;

    public static ManualRetryResult success(
            Long orderId, String operatorNotes, boolean retryCountReset) {
        return ManualRetryResult.builder()
                .orderId(orderId)
                .success(true)
                .operatorNotes(operatorNotes)
                .retryCountReset(retryCountReset)
                .build();
    }

    public static ManualRetryResult failed(Long orderId, String errorMessage) {
        return ManualRetryResult.builder()
                .orderId(orderId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }
}

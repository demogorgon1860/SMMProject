package com.smmpanel.dto.result;

import lombok.Builder;
import lombok.Data;

/** Result of DLQ operation */
@Builder
@Data
public class DlqOperationResult {
    private final Long orderId;
    private final boolean success;
    private final String message;

    public static DlqOperationResult success(Long orderId, String message) {
        return DlqOperationResult.builder().orderId(orderId).success(true).message(message).build();
    }

    public static DlqOperationResult failed(Long orderId, String message) {
        return DlqOperationResult.builder()
                .orderId(orderId)
                .success(false)
                .message(message)
                .build();
    }
}

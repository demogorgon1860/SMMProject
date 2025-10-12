package com.smmpanel.dto.result;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ErrorRecoveryResult {
    private final Long orderId;
    private final boolean success;
    private final String errorMessage;
    private final ErrorRecoveryAction action;
    private final LocalDateTime nextRetryTime;
    private final int retryCount;

    public static ErrorRecoveryResult retryScheduled(
            Long orderId, LocalDateTime nextRetryTime, int retryCount) {
        return ErrorRecoveryResult.builder()
                .orderId(orderId)
                .success(true)
                .action(ErrorRecoveryAction.RETRY_SCHEDULED)
                .nextRetryTime(nextRetryTime)
                .retryCount(retryCount)
                .build();
    }

    public static ErrorRecoveryResult deadLetterQueue(Long orderId, String reason, int retryCount) {
        return ErrorRecoveryResult.builder()
                .orderId(orderId)
                .success(true)
                .action(ErrorRecoveryAction.DEAD_LETTER_QUEUE)
                .errorMessage(reason)
                .retryCount(retryCount)
                .build();
    }

    public static ErrorRecoveryResult failed(Long orderId, String errorMessage) {
        return ErrorRecoveryResult.builder()
                .orderId(orderId)
                .success(false)
                .action(ErrorRecoveryAction.ERROR)
                .errorMessage(errorMessage)
                .build();
    }
}

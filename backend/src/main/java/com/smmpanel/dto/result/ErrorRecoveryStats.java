package com.smmpanel.dto.result;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/** Error recovery statistics */
@Builder
@Data
public class ErrorRecoveryStats {
    private final long totalFailedOrders;
    private final long failedLast24Hours;
    private final long failedLastWeek;
    private final long deadLetterQueueCount;
    private final long pendingRetries;
    private final List<ErrorTypeStats> errorTypeBreakdown;

    public static ErrorRecoveryStats empty() {
        return ErrorRecoveryStats.builder()
                .totalFailedOrders(0)
                .failedLast24Hours(0)
                .failedLastWeek(0)
                .deadLetterQueueCount(0)
                .pendingRetries(0)
                .errorTypeBreakdown(List.of())
                .build();
    }
}

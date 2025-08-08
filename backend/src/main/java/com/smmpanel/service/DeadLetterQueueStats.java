package com.smmpanel.service;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * Dead letter queue statistics
 */
@Builder
@Data
public class DeadLetterQueueStats {
    private final long totalDlqOrders;
    private final long dlqLast24Hours;
    private final long dlqLastWeek;
    private final long messagesReceived;
    private final long messagesProcessed;
    private final long messagesFailed;
    private final List<DlqErrorTypeStats> errorTypeBreakdown;

    public static DeadLetterQueueStats empty() {
        return DeadLetterQueueStats.builder()
                .totalDlqOrders(0)
                .dlqLast24Hours(0)
                .dlqLastWeek(0)
                .messagesReceived(0)
                .messagesProcessed(0)
                .messagesFailed(0)
                .errorTypeBreakdown(List.of())
                .build();
    }
}
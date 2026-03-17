package com.smmpanel.dto.telegram;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelPendingDecision {

    private Long orderId;
    private String botOrderId;
    private Integer telegramMessageId;
    private LocalDateTime createdAt;
    private String orderStatusAtTime;
}

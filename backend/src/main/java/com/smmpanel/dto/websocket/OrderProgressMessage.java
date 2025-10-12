package com.smmpanel.dto.websocket;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProgressMessage {
    private Long orderId;
    private Integer completedQuantity;
    private Integer totalQuantity;
    private Double progressPercentage;
    private LocalDateTime estimatedCompletion;
    private LocalDateTime timestamp;
}

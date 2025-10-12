package com.smmpanel.dto.websocket;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderUpdateMessage {
    private Long orderId;
    private String status;
    private Double progress;
    private String message;
    private LocalDateTime timestamp;
    private Map<String, Object> metadata;
}

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
public class NotificationMessage {
    private String type; // SUCCESS, INFO, WARNING, ERROR
    private String title;
    private String message;
    private LocalDateTime timestamp;
}

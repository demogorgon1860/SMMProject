package com.smmpanel.service.monitoring;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Alert {
    private AlertLevel level;
    private String message;
    private Map<String, Object> details;
    private LocalDateTime timestamp;
}

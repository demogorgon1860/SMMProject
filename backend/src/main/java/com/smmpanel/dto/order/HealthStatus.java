package com.smmpanel.dto.order;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthStatus {
    private String status; // UP, DOWN, DEGRADED
    private Map<String, Object> components;
    private LocalDateTime timestamp;
}

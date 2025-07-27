package com.smmpanel.dto.order;

import lombok.*;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthStatus {
    private String status; // UP, DOWN, DEGRADED
    private Map<String, Object> components;
    private LocalDateTime timestamp;
} 
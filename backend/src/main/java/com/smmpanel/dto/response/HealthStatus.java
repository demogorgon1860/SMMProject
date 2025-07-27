package com.smmpanel.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthStatus {
    private String status;
    private LocalDateTime timestamp;
    private boolean healthy;
    private Map<String, Object> details;
    
    public boolean isHealthy() {
        return healthy;
    }
} 
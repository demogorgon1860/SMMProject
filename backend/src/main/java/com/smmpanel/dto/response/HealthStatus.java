package com.smmpanel.dto.response;

import lombok.Data;
import java.util.Map;

@Data
public class HealthStatus {
    private boolean healthy;
    private String status;
    private Map<String, Object> details;
} 
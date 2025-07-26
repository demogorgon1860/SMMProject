package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.util.Map;

@Data
@Builder
public class HealthStatusResponse {
    private String status; // "UP", "DOWN", "DEGRADED"
    private Map<String, String> components;
    private String timestamp;
} 
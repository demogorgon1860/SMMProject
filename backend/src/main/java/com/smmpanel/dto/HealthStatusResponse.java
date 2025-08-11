package com.smmpanel.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HealthStatusResponse {
    private String status; // "UP", "DOWN", "DEGRADED"
    private Map<String, String> components;
    private String timestamp;
}

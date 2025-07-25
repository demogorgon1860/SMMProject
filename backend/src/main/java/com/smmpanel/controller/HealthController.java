package com.smmpanel.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v2/health")
public class HealthController {

    @GetMapping("/detailed")
    public ResponseEntity<Map<String, Object>> getDetailedHealth() {
        Map<String, Object> health = Map.of(
                "status", "UP",
                "overall", true,
                "timestamp", System.currentTimeMillis()
        );
        return ResponseEntity.ok(health);
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, String>> forceHealthCheck() {
        monitoringService.forceHealthCheck();
        return ResponseEntity.ok(Map.of("status", "completed"));
    }
}

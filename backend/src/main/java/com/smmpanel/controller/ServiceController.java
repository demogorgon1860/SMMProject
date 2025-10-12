package com.smmpanel.controller;

import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.service.core.ServiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/service")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceService serviceService;

    @GetMapping("/services")
    public ResponseEntity<PerfectPanelResponse> getServices() {
        return ResponseEntity.ok(
                PerfectPanelResponse.success(serviceService.getAllActiveServices()));
    }
}

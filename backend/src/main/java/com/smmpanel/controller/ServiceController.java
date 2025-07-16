package com.smmpanel.controller;

import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.service.ServiceManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceManagementService serviceManagementService;

    @GetMapping("/services")
    public ResponseEntity<PerfectPanelResponse> getServices() {
        return ResponseEntity.ok(PerfectPanelResponse.builder()
                .services(serviceManagementService.getAllActiveServices())
                .build());
    }
}
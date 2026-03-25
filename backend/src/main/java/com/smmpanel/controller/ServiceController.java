package com.smmpanel.controller;

import com.smmpanel.dto.response.PerfectPanelResponse;
import com.smmpanel.entity.User;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.core.ServiceService;
import java.security.Principal;
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
    private final UserRepository userRepository;

    @GetMapping("/services")
    public ResponseEntity<PerfectPanelResponse> getServices(Principal principal) {
        User user =
                userRepository
                        .findByUsername(principal.getName())
                        .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(
                PerfectPanelResponse.success(serviceService.getActiveServicesForUser(user)));
    }
}

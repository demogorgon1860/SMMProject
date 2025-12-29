package com.smmpanel.controller;

import com.smmpanel.dto.response.ApiKeyResponse;
import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.auth.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/apikey")
@RequiredArgsConstructor
@Tag(name = "API Key Management", description = "Endpoints for managing API keys")
@SecurityRequirement(name = "bearerAuth")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserRepository userRepository;

    @PostMapping("/generate")
    @Operation(
            summary = "Generate new API key",
            description = "Generates a new API key for the authenticated user")
    public ResponseEntity<ApiKeyResponse> generateApiKey() {
        User user = getCurrentUser();
        String apiKey = apiKeyService.generateApiKey(user.getId());

        ApiKeyResponse response = new ApiKeyResponse();
        response.setApiKey(apiKey);
        response.setMessage(
                "API key generated successfully. Please save it securely as it won't be shown"
                        + " again.");

        log.info("Generated new API key for user: {}", user.getUsername());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/rotate")
    @Operation(
            summary = "Rotate API key",
            description = "Rotates the API key - deactivates old key and creates new active one")
    public ResponseEntity<ApiKeyResponse> rotateApiKey() {
        User user = getCurrentUser();
        String newApiKey = apiKeyService.rotateApiKey(user.getId());

        ApiKeyResponse response = new ApiKeyResponse();
        response.setApiKey(newApiKey);
        response.setMessage("API key rotated successfully. Old key has been deactivated.");

        log.info("Rotated API key for user: {}", user.getUsername());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    @Operation(
            summary = "Get API key status",
            description = "Returns masked API key, active status, and last rotation date")
    public ResponseEntity<Map<String, Object>> getApiKeyStatus() {
        User user = getCurrentUser();

        Map<String, Object> response = new HashMap<>();
        response.put("maskedKey", apiKeyService.getMaskedApiKey(user.getId()));
        response.put("hasApiKey", user.getApiKeyHash() != null);
        response.put("isActive", user.getApiKeyActive() != null && user.getApiKeyActive());
        response.put("lastRotated", user.getApiKeyLastRotated());

        return ResponseEntity.ok(response);
    }

    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        return userRepository
                .findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));
    }
}

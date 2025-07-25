package com.smmpanel.controller;

import com.smmpanel.entity.User;
import com.smmpanel.exception.ResourceNotFoundException;
import com.smmpanel.security.CurrentUser;
import com.smmpanel.service.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/account/api-keys")
@RequiredArgsConstructor
@Tag(name = "API Key Management", description = "Endpoints for managing user API keys")
@SecurityRequirement(name = "bearerAuth")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(summary = "Generate a new API key", 
               description = "Generates a new API key for the authenticated user. Returns the new API key (only shown once).")
    public ResponseEntity<Map<String, String>> generateApiKey(@CurrentUser User user) {
        String apiKey = apiKeyService.generateApiKey(user.getId());
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "API key generated successfully");
        response.put("apiKey", apiKey);
        response.put("warning", "Store this API key securely. It will not be shown again.");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(summary = "Get API key info", 
               description = "Returns information about the user's API key (masked for security).")
    public ResponseEntity<Map<String, String>> getApiKeyInfo(@CurrentUser User user) {
        String maskedKey = apiKeyService.getMaskedApiKey(user.getId());
        
        Map<String, String> response = new HashMap<>();
        response.put("hasApiKey", !"No API key set".equals(maskedKey) ? "true" : "false");
        response.put("maskedKey", maskedKey);
        
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(summary = "Revoke API key", 
               description = "Revokes the current API key for the authenticated user.")
    public ResponseEntity<Map<String, String>> revokeApiKey(@CurrentUser User user) {
        apiKeyService.revokeApiKey(user.getId());
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "API key revoked successfully");
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/rotate")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN') or hasRole('OPERATOR')")
    @Operation(summary = "Rotate API key", 
               description = "Revokes the current API key and generates a new one. Returns the new API key (only shown once).")
    public ResponseEntity<Map<String, String>> rotateApiKey(@CurrentUser User user) {
        String newApiKey = apiKeyService.rotateApiKey(user.getId());
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "API key rotated successfully");
        response.put("newApiKey", newApiKey);
        response.put("warning", "Store this new API key securely. It will not be shown again.");
        
        return ResponseEntity.ok(response);
    }
}

package com.smmpanel.controller;

import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth2 Controller for handling OAuth2-related endpoints Provides OAuth2 login URLs and
 * configuration
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    @Value("${spring.security.oauth2.client.registration.google.client-id:}")
    private String googleClientId;

    @Value("${spring.security.oauth2.client.registration.github.client-id:}")
    private String githubClientId;

    @Value("${server.port:8080}")
    private String serverPort;

    /** Get available OAuth2 providers and their login URLs */
    @GetMapping("/providers")
    public ResponseEntity<Map<String, Object>> getOAuth2Providers() {
        Map<String, Object> providers = new HashMap<>();

        // Add Google provider if configured
        if (!googleClientId.isEmpty()) {
            providers.put(
                    "google",
                    Map.of(
                            "name", "Google",
                            "loginUrl", "/api/v1/auth/oauth2/authorization/google",
                            "enabled", true,
                            "icon", "google"));
        }

        // Add GitHub provider if configured
        if (!githubClientId.isEmpty()) {
            providers.put(
                    "github",
                    Map.of(
                            "name", "GitHub",
                            "loginUrl", "/api/v1/auth/oauth2/authorization/github",
                            "enabled", true,
                            "icon", "github"));
        }

        return ResponseEntity.ok(Map.of("providers", providers, "enabled", !providers.isEmpty()));
    }

    /**
     * OAuth2 callback endpoint (handled by Spring Security) This is just for
     * documentation/visibility
     */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, String>> handleCallback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {

        if (error != null) {
            log.error("OAuth2 callback error: {}", error);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", error));
        }

        log.info("OAuth2 callback received with code: {}", code != null ? "present" : "missing");

        return ResponseEntity.ok(
                Map.of(
                        "status", "success",
                        "message", "OAuth2 callback processed"));
    }

    /** Get OAuth2 configuration for frontend */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getOAuth2Config() {
        Map<String, Object> config = new HashMap<>();

        config.put("baseUrl", "http://localhost:" + serverPort);
        config.put("authorizationBaseUrl", "/api/v1/auth/oauth2/authorization");
        config.put("callbackUrl", "/login/oauth2/code");

        Map<String, Boolean> providers = new HashMap<>();
        providers.put("google", !googleClientId.isEmpty());
        providers.put("github", !githubClientId.isEmpty());
        config.put("providers", providers);

        return ResponseEntity.ok(config);
    }
}

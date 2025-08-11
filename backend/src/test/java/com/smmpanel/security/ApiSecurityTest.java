package com.smmpanel.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** API Security Tests Validates API security configurations and protections */
@SpringBootTest
@AutoConfigureMockMvc
public class ApiSecurityTest {

    @Autowired private MockMvc mockMvc;

    @Test
    public void testCorsConfiguration() throws Exception {
        mockMvc.perform(
                        options("/api/v1/users")
                                .header("Origin", "https://example.com")
                                .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"))
                .andExpect(header().exists("Access-Control-Allow-Methods"))
                .andExpect(header().exists("Access-Control-Max-Age"));
    }

    @Test
    public void testSecurityHeaders() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/public/health"))
                        .andExpect(status().isOk())
                        .andReturn();

        // Verify security headers
        assertTrue(result.getResponse().containsHeader("Content-Security-Policy"));
        assertTrue(result.getResponse().containsHeader("X-Content-Type-Options"));
        assertTrue(result.getResponse().containsHeader("X-Frame-Options"));
        assertTrue(result.getResponse().containsHeader("X-XSS-Protection"));
        assertTrue(result.getResponse().containsHeader("Strict-Transport-Security"));
        assertTrue(result.getResponse().containsHeader("Referrer-Policy"));
    }

    @Test
    public void testApiVersionValidation() throws Exception {
        // Test missing version header
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isBadRequest());

        // Test invalid version
        mockMvc.perform(get("/api/v1/users").header("X-Api-Version", "0.9"))
                .andExpect(status().isBadRequest());

        // Test valid version
        mockMvc.perform(get("/api/v1/users").header("X-Api-Version", "1.0"))
                .andExpect(status().isUnauthorized()); // Should fail due to missing auth
    }

    @Test
    public void testRequestValidation() throws Exception {
        // Test URL length limit
        StringBuilder longUrl = new StringBuilder("/api/v1/users?q=");
        for (int i = 0; i < 2000; i++) {
            longUrl.append("a");
        }
        mockMvc.perform(get(longUrl.toString())).andExpect(status().isUriTooLong());

        // Test content type validation
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("invalid content"))
                .andExpect(status().isUnsupportedMediaType());

        // Test request ID generation
        MvcResult result =
                mockMvc.perform(get("/api/v1/public/health"))
                        .andExpect(status().isOk())
                        .andExpect(header().exists("X-Request-ID"))
                        .andReturn();
    }

    @Test
    public void testCsrfProtection() throws Exception {
        // Test CSRF protection for state-changing operations
        mockMvc.perform(
                        post("/api/v1/users")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"test\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testRateLimiting() throws Exception {
        // Perform multiple requests to trigger rate limiting
        for (int i = 0; i < 101; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/public/health")).andReturn();

            if (i < 100) {
                assertEquals(200, result.getResponse().getStatus());
            } else {
                assertEquals(429, result.getResponse().getStatus()); // Too Many Requests
            }
        }
    }

    @Test
    public void testAuthenticationBypass() throws Exception {
        // Test accessing protected endpoint without authentication
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());

        // Test with invalid token
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized());
    }
}

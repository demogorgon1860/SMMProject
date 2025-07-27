package com.smmpanel.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.repository.ServiceRepository;
import com.smmpanel.repository.UserRepository;
import com.smmpanel.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Perfect Panel API Integration Tests
 * Tests Perfect Panel API compatibility and performance
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class PerfectPanelApiTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;
    private User testUser;
    private String testApiKey;
    private Service testService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        
        // Create test user
        testUser = User.builder()
            .username("testuser")
            .email("test@example.com")
            .passwordHash("password")
            .balance(new BigDecimal("100.00"))
            .isActive(true)
            .build();
        testUser = userRepository.save(testUser);
        
        // Generate API key
        testApiKey = apiKeyService.generateApiKey(testUser.getId());
        
        // Create test service
        testService = Service.builder()
            .name("YouTube Views")
            .category("YouTube")
            .pricePer1000(new BigDecimal("1.50"))
            .minOrder(100)
            .maxOrder(10000)
            .active(true)
            .build();
        testService = serviceRepository.save(testService);
    }

    @Test
    void testAddOrder() throws Exception {
        String requestBody = String.format(
            "key=%s&action=add&service=%d&link=https://www.youtube.com/watch?v=dQw4w9WgXcQ&quantity=1000",
            testApiKey, testService.getId()
        );

        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.order").exists())
            .andExpect(jsonPath("$.order").isNumber());
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;
        
        // Verify response time is under 200ms
        assertTrue(responseTime < 200, "Response time should be under 200ms, was: " + responseTime);
    }

    @Test
    void testGetServices() throws Exception {
        String requestBody = String.format("key=%s&action=services", testApiKey);

        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$[0].service").exists())
            .andExpect(jsonPath("$[0].name").exists())
            .andExpect(jsonPath("$[0].category").exists())
            .andExpect(jsonPath("$[0].rate").exists())
            .andExpect(jsonPath("$[0].min").exists())
            .andExpect(jsonPath("$[0].max").exists());
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;
        
        // Verify response time is under 200ms
        assertTrue(responseTime < 200, "Response time should be under 200ms, was: " + responseTime);
    }

    @Test
    void testGetBalance() throws Exception {
        String requestBody = String.format("key=%s&action=balance", testApiKey);

        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.balance").exists())
            .andExpect(jsonPath("$.currency").value("USD"));
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;
        
        // Verify response time is under 200ms
        assertTrue(responseTime < 200, "Response time should be under 200ms, was: " + responseTime);
    }

    @Test
    void testOrderStatus() throws Exception {
        // First create an order
        String addOrderBody = String.format(
            "key=%s&action=add&service=%d&link=https://www.youtube.com/watch?v=dQw4w9WgXcQ&quantity=1000",
            testApiKey, testService.getId()
        );

        String addResponse = mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(addOrderBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        Map<String, Object> addResult = objectMapper.readValue(addResponse, Map.class);
        Long orderId = Long.valueOf(addResult.get("order").toString());

        // Then check status
        String statusBody = String.format("key=%s&action=status&order=%d", testApiKey, orderId);

        long startTime = System.currentTimeMillis();
        
        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(statusBody))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.order").value(orderId))
            .andExpect(jsonPath("$.status").exists())
            .andExpect(jsonPath("$.remains").exists())
            .andExpect(jsonPath("$.start_count").exists())
            .andExpect(jsonPath("$.charge").exists())
            .andExpect(jsonPath("$.currency").value("USD"));
        
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;
        
        // Verify response time is under 200ms
        assertTrue(responseTime < 200, "Response time should be under 200ms, was: " + responseTime);
    }

    @Test
    void testInvalidApiKey() throws Exception {
        String requestBody = "key=invalid_key&action=balance";

        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testMissingParameters() throws Exception {
        String requestBody = String.format("key=%s&action=add", testApiKey);

        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testInvalidAction() throws Exception {
        String requestBody = String.format("key=%s&action=invalid_action", testApiKey);

        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void testConcurrentRequests() throws Exception {
        String requestBody = String.format("key=%s&action=balance", testApiKey);
        
        // Test concurrent requests
        List<Thread> threads = new java.util.ArrayList<>();
        List<Long> responseTimes = new java.util.ArrayList<>();
        
        for (int i = 0; i < 10; i++) {
            Thread thread = new Thread(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    mockMvc.perform(post("/api/v2")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content(requestBody))
                        .andExpect(status().isOk());
                    long endTime = System.currentTimeMillis();
                    synchronized (responseTimes) {
                        responseTimes.add(endTime - startTime);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify all response times are under 200ms
        for (Long responseTime : responseTimes) {
            assertTrue(responseTime < 200, "Response time should be under 200ms, was: " + responseTime);
        }
        
        // Verify average response time is reasonable
        double avgResponseTime = responseTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);
        
        assertTrue(avgResponseTime < 100, "Average response time should be under 100ms, was: " + avgResponseTime);
    }

    @Test
    void testRateLimiting() throws Exception {
        String requestBody = String.format("key=%s&action=balance", testApiKey);
        
        // Make multiple requests quickly to trigger rate limiting
        for (int i = 0; i < 150; i++) {
            mockMvc.perform(post("/api/v2")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content(requestBody));
        }
        
        // The 101st request should be rate limited
        mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    void testPerfectPanelResponseFormat() throws Exception {
        String requestBody = String.format("key=%s&action=balance", testApiKey);

        String response = mockMvc.perform(post("/api/v2")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(requestBody))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        Map<String, Object> result = objectMapper.readValue(response, Map.class);
        
        // Verify Perfect Panel response format
        assertTrue(result.containsKey("balance"), "Response should contain 'balance' field");
        assertTrue(result.containsKey("currency"), "Response should contain 'currency' field");
        assertEquals("USD", result.get("currency"), "Currency should be USD");
        
        // Verify balance format (2 decimal places)
        String balance = result.get("balance").toString();
        assertTrue(balance.matches("\\d+\\.\\d{2}"), "Balance should have 2 decimal places: " + balance);
    }
} 
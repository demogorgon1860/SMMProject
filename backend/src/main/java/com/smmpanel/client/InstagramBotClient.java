package com.smmpanel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.instagram.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for communicating with the Instagram bot API.
 * Bot is located at C:\Users\user\Desktop\instagramBot (Go-based).
 */
@Slf4j
@Component
public class InstagramBotClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final Retry readRetry;
    private final Retry writeRetry;

    @Value("${app.instagram.bot.url:http://45.142.211.90:8080}")
    private String botBaseUrl;

    @Value("${app.instagram.bot.callback-url:}")
    private String callbackBaseUrl;

    public InstagramBotClient(
            @Qualifier("instagramBotRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Qualifier("instagramBotCircuitBreaker") CircuitBreaker circuitBreaker,
            @Qualifier("instagramBotReadRetry") Retry readRetry,
            @Qualifier("instagramBotWriteRetry") Retry writeRetry) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.readRetry = readRetry;
        this.writeRetry = writeRetry;
    }

    /**
     * Create a new order in the Instagram bot.
     * POST /api/orders/create
     *
     * @param request Order creation request
     * @return Response with bot order ID
     */
    public InstagramOrderResponse createOrder(InstagramOrderRequest request) {
        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    try {
                                        String url = botBaseUrl + "/api/orders/create";

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);

                                        String jsonPayload = objectMapper.writeValueAsString(request);
                                        log.info("Creating Instagram order: {}", jsonPayload);

                                        HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

                                        ResponseEntity<Map> response = restTemplate.exchange(
                                                url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                                            Map<String, Object> body = response.getBody();

                                            Boolean success = (Boolean) body.get("success");
                                            String id = (String) body.get("id");
                                            String error = (String) body.get("error");

                                            if (Boolean.TRUE.equals(success)) {
                                                log.info("Instagram order created successfully: {}", id);
                                                return InstagramOrderResponse.builder()
                                                        .success(true)
                                                        .id(id)
                                                        .build();
                                            } else {
                                                log.error("Instagram bot returned error: {}", error);
                                                return InstagramOrderResponse.builder()
                                                        .success(false)
                                                        .error(error != null ? error : "Unknown error")
                                                        .build();
                                            }
                                        }

                                        throw new RuntimeException("Invalid response from Instagram bot");

                                    } catch (Exception e) {
                                        log.error("Failed to create Instagram order: {}", e.getMessage(), e);
                                        throw new RuntimeException("Order creation failed", e);
                                    }
                                }));
    }

    /**
     * Get order status from the bot.
     * GET /api/orders/get?id=X
     *
     * @param orderId Bot's order ID
     * @return Order status
     */
    public InstagramOrderStatus getOrderStatus(String orderId) {
        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    try {
                                        String url = botBaseUrl + "/api/orders/get?id=" + orderId;

                                        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                                            Map<String, Object> body = response.getBody();
                                            return InstagramOrderStatus.builder()
                                                    .id((String) body.get("id"))
                                                    .type((String) body.get("type"))
                                                    .targetUrl((String) body.get("target_url"))
                                                    .count((Integer) body.get("count"))
                                                    .externalId((String) body.get("external_id"))
                                                    .status((String) body.get("status"))
                                                    .completed((Integer) body.get("completed"))
                                                    .failed((Integer) body.get("failed"))
                                                    .createdAt((String) body.get("created_at"))
                                                    .startedAt((String) body.get("started_at"))
                                                    .completedAt((String) body.get("completed_at"))
                                                    .build();
                                        }

                                        throw new RuntimeException("Failed to get order status from Instagram bot");

                                    } catch (Exception e) {
                                        log.error("Failed to get Instagram order status: {}", e.getMessage(), e);
                                        throw new RuntimeException("Order status retrieval failed", e);
                                    }
                                }));
    }

    /**
     * Cancel an order in the bot.
     * POST /api/orders/cancel
     *
     * @param orderId Bot's order ID
     * @return true if cancelled successfully
     */
    public boolean cancelOrder(String orderId) {
        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    try {
                                        String url = botBaseUrl + "/api/orders/cancel";

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);

                                        Map<String, String> body = new HashMap<>();
                                        body.put("order_id", orderId);

                                        String jsonPayload = objectMapper.writeValueAsString(body);
                                        HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

                                        ResponseEntity<Map> response = restTemplate.exchange(
                                                url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                                            Boolean success = (Boolean) response.getBody().get("success");
                                            if (Boolean.TRUE.equals(success)) {
                                                log.info("Instagram order {} cancelled", orderId);
                                                return true;
                                            }
                                        }

                                        log.warn("Failed to cancel Instagram order {}", orderId);
                                        return false;

                                    } catch (Exception e) {
                                        log.error("Error cancelling Instagram order: {}", e.getMessage());
                                        throw new RuntimeException("Order cancellation failed", e);
                                    }
                                }));
    }

    /**
     * List all orders from the bot.
     * GET /api/orders
     *
     * @return List of orders
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listOrders() {
        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    try {
                                        String url = botBaseUrl + "/api/orders";

                                        ResponseEntity<List> response = restTemplate.getForEntity(url, List.class);

                                        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                                            return response.getBody();
                                        }

                                        return List.of();

                                    } catch (Exception e) {
                                        log.error("Error listing Instagram orders: {}", e.getMessage());
                                        throw new RuntimeException("Order list retrieval failed", e);
                                    }
                                }));
    }

    /**
     * Get queue statistics from the bot.
     * GET /api/orders/stats
     *
     * @return Stats map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getQueueStats() {
        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    try {
                                        String url = botBaseUrl + "/api/orders/stats";

                                        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                                            return response.getBody();
                                        }

                                        return Map.of();

                                    } catch (Exception e) {
                                        log.error("Error getting queue stats: {}", e.getMessage());
                                        throw new RuntimeException("Queue stats retrieval failed", e);
                                    }
                                }));
    }

    /**
     * Control workers (start/stop).
     * POST /api/orders/workers
     *
     * @param action "start" or "stop"
     * @return true if successful
     */
    public boolean controlWorkers(String action) {
        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    try {
                                        String url = botBaseUrl + "/api/orders/workers";

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);

                                        Map<String, String> body = new HashMap<>();
                                        body.put("action", action);

                                        String jsonPayload = objectMapper.writeValueAsString(body);
                                        HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

                                        ResponseEntity<Map> response = restTemplate.exchange(
                                                url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK) {
                                            log.info("Workers action '{}' executed successfully", action);
                                            return true;
                                        }

                                        return false;

                                    } catch (Exception e) {
                                        log.error("Error controlling workers: {}", e.getMessage());
                                        throw new RuntimeException("Worker control failed", e);
                                    }
                                }));
    }

    /**
     * Check bot health.
     * GET /api/health
     *
     * @return Health response
     */
    @SuppressWarnings("unchecked")
    public InstagramHealthResponse checkHealth() {
        try {
            String url = botBaseUrl + "/api/health";

            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                return InstagramHealthResponse.builder()
                        .status((String) body.get("status"))
                        .components((Map<String, Object>) body.get("components"))
                        .version((String) body.get("version"))
                        .uptime(body.get("uptime") != null ? ((Number) body.get("uptime")).longValue() : null)
                        .build();
            }

            return InstagramHealthResponse.builder()
                    .status("unknown")
                    .build();

        } catch (Exception e) {
            log.error("Error checking Instagram bot health: {}", e.getMessage());
            return InstagramHealthResponse.builder()
                    .status("error")
                    .build();
        }
    }

    /**
     * Check if bot is alive (liveness probe).
     * GET /api/health/live
     *
     * @return true if alive
     */
    public boolean isAlive() {
        try {
            String url = botBaseUrl + "/api/health/live";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.debug("Instagram bot liveness check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if bot is ready to accept requests (readiness probe).
     * GET /api/health/ready
     *
     * @return true if ready
     */
    public boolean isReady() {
        try {
            String url = botBaseUrl + "/api/health/ready";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.debug("Instagram bot readiness check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get the callback URL for webhooks.
     */
    public String getCallbackUrl() {
        return callbackBaseUrl + "/api/webhook/instagram";
    }
}

package com.smmpanel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.instagram.*;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for communicating with multiple Instagram bot instances. Supports competing consumers
 * pattern where multiple bots consume from the same RabbitMQ queue. HTTP calls are used for health
 * checks, progress polling, order cancellation, and stats aggregation.
 */
@Slf4j
@Component
public class InstagramBotClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @Value("${app.instagram.bot.urls:}")
    private String botUrls;

    @Value("${app.instagram.bot.url:http://45.142.211.90:8080}")
    private String botBaseUrl;

    @Value("${app.instagram.bot.callback-url:}")
    private String callbackBaseUrl;

    private List<String> botInstances;
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, Retry> readRetries = new ConcurrentHashMap<>();
    private final Map<String, Retry> writeRetries = new ConcurrentHashMap<>();
    // Round-robin counter for distributing new orders across bot instances
    private final java.util.concurrent.atomic.AtomicInteger rrCounter =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public InstagramBotClient(
            @Qualifier("instagramBotRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    @PostConstruct
    public void init() {
        if (botUrls != null && !botUrls.isBlank()) {
            botInstances =
                    Arrays.stream(botUrls.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
        } else {
            botInstances = List.of(botBaseUrl);
        }

        for (int i = 0; i < botInstances.size(); i++) {
            String instance = botInstances.get(i);
            String suffix = "bot" + (i + 1);
            circuitBreakers.put(
                    instance, circuitBreakerRegistry.circuitBreaker("instagramBot-" + suffix));
            readRetries.put(instance, retryRegistry.retry("instagramBotRead-" + suffix));
            writeRetries.put(instance, retryRegistry.retry("instagramBotWrite-" + suffix));
        }

        log.info(
                "Instagram bot client initialized with {} instance(s): {}",
                botInstances.size(),
                botInstances);
    }

    /** Returns the list of configured bot instance URLs. */
    public List<String> getBotInstances() {
        return botInstances;
    }

    /**
     * Create a new order in the Instagram bot. POST /api/orders/create Uses round-robin to
     * distribute orders across bot instances, falling back to the next instance on failure.
     */
    public InstagramOrderResponse createOrder(InstagramOrderRequest request) {
        int size = botInstances.size();
        int startIdx = Math.abs(rrCounter.getAndIncrement() % size);
        for (int i = 0; i < size; i++) {
            String instance = botInstances.get((startIdx + i) % size);
            try {
                return executeOnInstance(
                        instance,
                        true,
                        () -> {
                            String url = instance + "/api/orders/create";

                            HttpHeaders headers = new HttpHeaders();
                            headers.setContentType(MediaType.APPLICATION_JSON);

                            String jsonPayload = objectMapper.writeValueAsString(request);
                            log.info("Creating Instagram order on {}: {}", instance, jsonPayload);

                            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

                            ResponseEntity<Map> response =
                                    restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

                            if (response.getStatusCode() == HttpStatus.OK
                                    && response.getBody() != null) {
                                Map<String, Object> body = response.getBody();
                                Boolean success = (Boolean) body.get("success");
                                String id = (String) body.get("id");
                                String error = (String) body.get("error");

                                if (Boolean.TRUE.equals(success)) {
                                    log.info("Instagram order created on {}: {}", instance, id);
                                    return InstagramOrderResponse.builder()
                                            .success(true)
                                            .id(id)
                                            .build();
                                } else {
                                    return InstagramOrderResponse.builder()
                                            .success(false)
                                            .error(error != null ? error : "Unknown error")
                                            .build();
                                }
                            }
                            throw new RuntimeException("Invalid response from Instagram bot");
                        });
            } catch (Exception e) {
                log.warn("Failed to create order on {}: {}", instance, e.getMessage());
            }
        }
        return InstagramOrderResponse.builder()
                .success(false)
                .error("All bot instances unavailable")
                .build();
    }

    /** Get order status from the bot. Tries each bot instance until one returns the order. */
    public InstagramOrderStatus getOrderStatus(String orderId) {
        for (String instance : botInstances) {
            try {
                return executeOnInstance(
                        instance,
                        false,
                        () -> {
                            String url = instance + "/api/orders/get?id=" + orderId;
                            ResponseEntity<Map> response =
                                    restTemplate.getForEntity(url, Map.class);

                            if (response.getStatusCode() == HttpStatus.OK
                                    && response.getBody() != null) {
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
                            throw new RuntimeException("Order not found on " + instance);
                        });
            } catch (Exception e) {
                log.debug("Order {} not found on {}: {}", orderId, instance, e.getMessage());
            }
        }
        throw new RuntimeException("Order " + orderId + " not found on any bot instance");
    }

    /** Cancel an order. Tries each bot instance — succeeds if any one cancels it. */
    public boolean cancelOrder(String orderId) {
        for (String instance : botInstances) {
            try {
                boolean result =
                        executeOnInstance(
                                instance,
                                true,
                                () -> {
                                    String url = instance + "/api/orders/cancel";

                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);

                                    Map<String, String> body = new HashMap<>();
                                    body.put("order_id", orderId);

                                    String jsonPayload = objectMapper.writeValueAsString(body);
                                    HttpEntity<String> entity =
                                            new HttpEntity<>(jsonPayload, headers);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.POST, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Boolean success =
                                                (Boolean) response.getBody().get("success");
                                        if (Boolean.TRUE.equals(success)) {
                                            log.info(
                                                    "Instagram order {} cancelled on {}",
                                                    orderId,
                                                    instance);
                                            return true;
                                        }
                                    }
                                    return false;
                                });
                if (result) return true;
            } catch (Exception e) {
                log.debug("Cancel failed on {}: {}", instance, e.getMessage());
            }
        }
        log.warn("Failed to cancel Instagram order {} on any instance", orderId);
        return false;
    }

    /**
     * Resume a paused order. Called when admin clicks "Продолжить" on a pending_cancel decision.
     * Tries each bot instance; succeeds if any one resumes it.
     */
    public boolean resumeOrder(String botOrderId) {
        for (String instance : botInstances) {
            try {
                boolean result =
                        executeOnInstance(
                                instance,
                                true,
                                () -> {
                                    String url = instance + "/api/orders/resume";

                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);

                                    Map<String, String> body = new HashMap<>();
                                    body.put("order_id", botOrderId);

                                    String jsonPayload = objectMapper.writeValueAsString(body);
                                    HttpEntity<String> entity =
                                            new HttpEntity<>(jsonPayload, headers);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.POST, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Boolean success =
                                                (Boolean) response.getBody().get("success");
                                        if (Boolean.TRUE.equals(success)) {
                                            log.info(
                                                    "Instagram order {} resumed on {}",
                                                    botOrderId,
                                                    instance);
                                            return true;
                                        }
                                    }
                                    return false;
                                });
                if (result) return true;
            } catch (Exception e) {
                log.debug("Resume failed on {}: {}", instance, e.getMessage());
            }
        }
        log.warn("Failed to resume Instagram order {} on any instance", botOrderId);
        return false;
    }

    /** List all orders from all bot instances. Merges results from each bot. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listOrders() {
        List<Map<String, Object>> allOrders = new ArrayList<>();
        for (String instance : botInstances) {
            try {
                List<Map<String, Object>> orders =
                        executeOnInstance(
                                instance,
                                false,
                                () -> {
                                    String url = instance + "/api/orders";
                                    ResponseEntity<Map> response =
                                            restTemplate.getForEntity(url, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Object o = response.getBody().get("orders");
                                        if (o instanceof List) {
                                            return (List<Map<String, Object>>) o;
                                        }
                                    }
                                    return List.<Map<String, Object>>of();
                                });
                allOrders.addAll(orders);
            } catch (Exception e) {
                log.warn("Failed to list orders from {}: {}", instance, e.getMessage());
            }
        }
        return allOrders;
    }

    /** Get all orders from all bot instances as typed DTOs. Merges results. */
    @SuppressWarnings("unchecked")
    public List<InstagramOrderStatus> getAllOrders() {
        List<InstagramOrderStatus> allOrders = new ArrayList<>();
        for (String instance : botInstances) {
            try {
                List<InstagramOrderStatus> orders =
                        executeOnInstance(
                                instance,
                                false,
                                () -> {
                                    String url = instance + "/api/orders";
                                    ResponseEntity<Map> response =
                                            restTemplate.getForEntity(url, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Object o = response.getBody().get("orders");
                                        if (o instanceof List) {
                                            List<Map<String, Object>> orderList =
                                                    (List<Map<String, Object>>) o;
                                            return orderList.stream()
                                                    .map(this::mapToOrderStatus)
                                                    .toList();
                                        }
                                    }
                                    return List.<InstagramOrderStatus>of();
                                });
                allOrders.addAll(orders);
            } catch (Exception e) {
                log.warn("Failed to get orders from {}: {}", instance, e.getMessage());
            }
        }
        return allOrders;
    }

    /** Get queue statistics from all bot instances. Returns per-instance stats. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getQueueStats() {
        if (botInstances.size() == 1) {
            return getQueueStatsFromInstance(botInstances.get(0));
        }

        Map<String, Object> aggregated = new LinkedHashMap<>();
        for (int i = 0; i < botInstances.size(); i++) {
            String instance = botInstances.get(i);
            try {
                Map<String, Object> stats = getQueueStatsFromInstance(instance);
                aggregated.put("bot" + (i + 1), stats);
            } catch (Exception e) {
                aggregated.put("bot" + (i + 1), Map.of("error", e.getMessage()));
            }
        }
        return aggregated;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getQueueStatsFromInstance(String instance) {
        return executeOnInstance(
                instance,
                false,
                () -> {
                    String url = instance + "/api/orders/stats";
                    ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        return response.getBody();
                    }
                    return Map.<String, Object>of();
                });
    }

    /** Control workers on all bot instances. Returns true if at least one succeeded. */
    public boolean controlWorkers(String action) {
        boolean anySuccess = false;
        for (String instance : botInstances) {
            try {
                boolean result =
                        executeOnInstance(
                                instance,
                                true,
                                () -> {
                                    String url = instance + "/api/orders/workers";

                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);

                                    Map<String, String> body = new HashMap<>();
                                    body.put("action", action);

                                    String jsonPayload = objectMapper.writeValueAsString(body);
                                    HttpEntity<String> entity =
                                            new HttpEntity<>(jsonPayload, headers);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.POST, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK) {
                                        log.info("Workers '{}' on {} succeeded", action, instance);
                                        return true;
                                    }
                                    return false;
                                });
                if (result) anySuccess = true;
            } catch (Exception e) {
                log.warn("Workers '{}' failed on {}: {}", action, instance, e.getMessage());
            }
        }
        return anySuccess;
    }

    /** Check health of all bot instances. Returns aggregated health with per-bot details. */
    @SuppressWarnings("unchecked")
    public InstagramHealthResponse checkHealth() {
        if (botInstances.size() == 1) {
            return checkHealthFromInstance(botInstances.get(0));
        }

        Map<String, Object> allComponents = new LinkedHashMap<>();
        String worstStatus = "healthy";

        for (int i = 0; i < botInstances.size(); i++) {
            String instance = botInstances.get(i);
            String label = "bot" + (i + 1);
            InstagramHealthResponse health = checkHealthFromInstance(instance);

            Map<String, Object> botInfo = new LinkedHashMap<>();
            botInfo.put("url", instance);
            botInfo.put("status", health.getStatus());
            if (health.getComponents() != null) {
                botInfo.put("components", health.getComponents());
            }
            if (health.getUptime() != null) {
                botInfo.put("uptime", health.getUptime());
            }
            allComponents.put(label, botInfo);

            if ("error".equals(health.getStatus()) || "unhealthy".equals(health.getStatus())) {
                if (!"error".equals(worstStatus)) {
                    worstStatus = "degraded";
                }
            }
        }

        return InstagramHealthResponse.builder()
                .status(worstStatus)
                .components(allComponents)
                .build();
    }

    private InstagramHealthResponse checkHealthFromInstance(String instance) {
        try {
            String url = instance + "/api/health";
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();
                return InstagramHealthResponse.builder()
                        .status((String) body.get("status"))
                        .components((Map<String, Object>) body.get("components"))
                        .version((String) body.get("version"))
                        .uptime(
                                body.get("uptime") != null
                                        ? ((Number) body.get("uptime")).longValue()
                                        : null)
                        .build();
            }
            return InstagramHealthResponse.builder().status("unknown").build();
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", instance, e.getMessage());
            return InstagramHealthResponse.builder().status("error").build();
        }
    }

    /** Returns true if at least one bot is alive. */
    public boolean isAlive() {
        for (String instance : botInstances) {
            try {
                String url = instance + "/api/health/live";
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode() == HttpStatus.OK) return true;
            } catch (Exception e) {
                log.debug("Liveness check failed for {}: {}", instance, e.getMessage());
            }
        }
        return false;
    }

    /** Returns true if at least one bot is ready. */
    public boolean isReady() {
        for (String instance : botInstances) {
            try {
                String url = instance + "/api/health/ready";
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
                if (response.getStatusCode() == HttpStatus.OK) return true;
            } catch (Exception e) {
                log.debug("Readiness check failed for {}: {}", instance, e.getMessage());
            }
        }
        return false;
    }

    /** Get the callback URL for webhooks. */
    public String getCallbackUrl() {
        return callbackBaseUrl + "/api/webhook/instagram";
    }

    // ==================== Internal Helpers ====================

    private InstagramOrderStatus mapToOrderStatus(Map<String, Object> body) {
        return InstagramOrderStatus.builder()
                .id((String) body.get("id"))
                .type((String) body.get("type"))
                .targetUrl((String) body.get("target_url"))
                .count((Integer) body.get("count"))
                .externalId((String) body.get("external_id"))
                .status((String) body.get("status"))
                .completed((Integer) body.get("completed"))
                .failed((Integer) body.get("failed"))
                .build();
    }

    /** Execute a supplier on a specific bot instance with its own circuit breaker and retry. */
    private <T> T executeOnInstance(
            String instance, boolean isWrite, SupplierWithException<T> supplier) {
        CircuitBreaker cb =
                circuitBreakers.getOrDefault(
                        instance, circuitBreakerRegistry.circuitBreaker("instagramBot"));
        Retry retry =
                isWrite
                        ? writeRetries.getOrDefault(
                                instance, retryRegistry.retry("instagramBotWrite"))
                        : readRetries.getOrDefault(
                                instance, retryRegistry.retry("instagramBotRead"));

        return cb.executeSupplier(
                () ->
                        retry.executeSupplier(
                                () -> {
                                    try {
                                        return supplier.get();
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                }));
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }
}

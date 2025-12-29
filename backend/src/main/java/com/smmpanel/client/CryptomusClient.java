package com.smmpanel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.cryptomus.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class CryptomusClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;
    private final io.github.resilience4j.retry.Retry readRetry;
    private final io.github.resilience4j.retry.Retry writeRetry;

    public CryptomusClient(
            @org.springframework.beans.factory.annotation.Qualifier("cryptomusRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Qualifier("cryptomusCircuitBreaker") io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker,
            @org.springframework.beans.factory.annotation.Qualifier("cryptomusReadRetry") io.github.resilience4j.retry.Retry readRetry,
            @org.springframework.beans.factory.annotation.Qualifier("cryptomusWriteRetry") io.github.resilience4j.retry.Retry writeRetry) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.readRetry = readRetry;
        this.writeRetry = writeRetry;
    }

    @Value("${app.cryptomus.api.url:https://api.cryptomus.com/v1}")
    private String apiUrl;

    @Value("${app.cryptomus.api.url:https://api.cryptomus.com/v2}")
    private String apiUrlV2;

    // Payment API Key (used for all operations)
    @Value("${app.cryptomus.api.payment-key}")
    private String paymentApiKey;

    @Value("${app.cryptomus.merchant-id:not-applicable}")
    private String merchantId;

    @Value("${app.cryptomus.api.secret:not-applicable}")
    private String apiSecret;

    /** POST /v1/payment - Creates an invoice for crypto payment */
    public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    try {
                                        String endpoint = "/payment";
                                        String url = apiUrl + endpoint;

                                        String jsonPayload =
                                                objectMapper.writeValueAsString(request);
                                        String sign = generateSignature(jsonPayload, false);

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);
                                        headers.set("merchant", merchantId);
                                        headers.set("sign", sign);

                                        HttpEntity<String> entity =
                                                new HttpEntity<>(jsonPayload, headers);

                                        log.debug("Creating Cryptomus payment: {}", jsonPayload);

                                        ResponseEntity<Map> response =
                                                restTemplate.exchange(
                                                        url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK) {
                                            Map<String, Object> responseBody =
                                                    getResponseBodyAsMap(response);

                                            if (responseBody != null
                                                    && responseBody.containsKey("result")) {
                                                Map<String, Object> result =
                                                        safeCastToMap(responseBody.get("result"));

                                                return CreatePaymentResponse.builder()
                                                        .uuid((String) result.get("uuid"))
                                                        .orderId((String) result.get("order_id"))
                                                        .amount(
                                                                new BigDecimal(
                                                                        String.valueOf(
                                                                                result.get(
                                                                                        "amount"))))
                                                        .currency((String) result.get("currency"))
                                                        .url((String) result.get("url"))
                                                        .status((String) result.get("status"))
                                                        .address((String) result.get("address"))
                                                        .network((String) result.get("network"))
                                                        .build();
                                            }
                                        }

                                        throw new RuntimeException(
                                                "Invalid response from Cryptomus API");

                                    } catch (Exception e) {
                                        log.error(
                                                "Failed to create Cryptomus payment: {}",
                                                e.getMessage(),
                                                e);
                                        throw new RuntimeException("Payment creation failed", e);
                                    }
                                }));
    }

    /**
     * POST /v1/payment/info - Returns information about a specific invoice Can use either uuid or
     * order_id
     */
    public PaymentInfoResponse getPaymentInfo(String uuid, String orderId) {
        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    try {
                                        String endpoint = "/payment/info";
                                        String url = apiUrl + endpoint;

                                        Map<String, String> requestBody = new HashMap<>();
                                        if (uuid != null) {
                                            requestBody.put("uuid", uuid);
                                        } else if (orderId != null) {
                                            requestBody.put("order_id", orderId);
                                        } else {
                                            throw new IllegalArgumentException(
                                                    "Either uuid or order_id must be provided");
                                        }

                                        String jsonPayload =
                                                objectMapper.writeValueAsString(requestBody);
                                        String sign = generateSignature(jsonPayload, false);

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);
                                        headers.set("merchant", merchantId);
                                        headers.set("sign", sign);

                                        HttpEntity<String> entity =
                                                new HttpEntity<>(jsonPayload, headers);

                                        ResponseEntity<Map> response =
                                                restTemplate.exchange(
                                                        url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK
                                                && response.getBody() != null) {
                                            Map<String, Object> responseBody =
                                                    getResponseBodyAsMap(response);
                                            if (responseBody.containsKey("result")) {
                                                Map<String, Object> result =
                                                        safeCastToMap(responseBody.get("result"));
                                                return PaymentInfoResponse.builder()
                                                        .uuid((String) result.get("uuid"))
                                                        .orderId((String) result.get("order_id"))
                                                        .amount(
                                                                new BigDecimal(
                                                                        String.valueOf(
                                                                                result.get(
                                                                                        "amount"))))
                                                        .currency((String) result.get("currency"))
                                                        .status((String) result.get("status"))
                                                        .paymentStatus(
                                                                (String)
                                                                        result.get(
                                                                                "payment_status"))
                                                        .url((String) result.get("url"))
                                                        .address((String) result.get("address"))
                                                        .network((String) result.get("network"))
                                                        .txid((String) result.get("txid"))
                                                        .build();
                                            }
                                        }

                                        throw new RuntimeException(
                                                "Failed to get payment info from Cryptomus");

                                    } catch (Exception e) {
                                        log.error(
                                                "Failed to get Cryptomus payment info: {}",
                                                e.getMessage(),
                                                e);
                                        throw new RuntimeException(
                                                "Payment info retrieval failed", e);
                                    }
                                }));
    }

    private String generateSignature(String data, boolean isUserApi) {
        try {
            // Use payment API key for all operations
            String apiKey = paymentApiKey;

            // Cryptomus signature algorithm (from docs):
            // $sign = md5(base64_encode($data) . $API_KEY);
            // 1. Base64 encode the JSON body
            // 2. Concatenate: base64(body) + apiKey
            // 3. MD5 hash the combined string (return as hex string)

            // Step 1: Base64 encode the data
            String base64Data =
                    Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));

            // Step 2: Concatenate with API key
            String combined = base64Data + apiKey;

            // Step 3: MD5 hash and convert to hex string
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md5.digest(combined.getBytes(StandardCharsets.UTF_8));

            // Convert MD5 bytes to hex string (NOT base64!)
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();

        } catch (Exception e) {
            log.error("Failed to generate signature: {}", e.getMessage());
            throw new RuntimeException("Signature generation failed", e);
        }
    }

    /** POST /v1/payment/services - Returns available payment services */
    public PaymentServicesResponse getPaymentServices() {
        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    try {
                                        String endpoint = "/payment/services";
                                        String url = apiUrl + endpoint;

                                        String jsonPayload = "{}"; // Empty JSON
                                        String sign = generateSignature(jsonPayload, false);

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);
                                        headers.set("merchant", merchantId);
                                        headers.set("sign", sign);

                                        HttpEntity<String> entity =
                                                new HttpEntity<>(jsonPayload, headers);

                                        ResponseEntity<Map> response =
                                                restTemplate.exchange(
                                                        url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK
                                                && response.getBody() != null) {
                                            Map<String, Object> responseBody =
                                                    getResponseBodyAsMap(response);
                                            if (responseBody.containsKey("result")) {
                                                List<Map<String, Object>> services =
                                                        safeCastToList(responseBody.get("result"));
                                                return PaymentServicesResponse.builder()
                                                        .services(services)
                                                        .build();
                                            }
                                        }

                                        throw new RuntimeException(
                                                "Failed to get payment services from Cryptomus");

                                    } catch (Exception e) {
                                        log.error(
                                                "Failed to get Cryptomus payment services: {}",
                                                e.getMessage(),
                                                e);
                                        throw new RuntimeException(
                                                "Payment services retrieval failed", e);
                                    }
                                }));
    }

    /** POST /v1/wallet - Creates a static crypto wallet for repeated payments */
    public CreateWalletResponse createWallet(CreateWalletRequest request) {
        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    try {
                                        String endpoint = "/wallet";
                                        String url = apiUrl + endpoint;

                                        String jsonPayload =
                                                objectMapper.writeValueAsString(request);
                                        String sign = generateSignature(jsonPayload, false);

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);
                                        headers.set("merchant", merchantId);
                                        headers.set("sign", sign);

                                        HttpEntity<String> entity =
                                                new HttpEntity<>(jsonPayload, headers);

                                        log.debug("Creating Cryptomus wallet: {}", jsonPayload);

                                        ResponseEntity<Map> response =
                                                restTemplate.exchange(
                                                        url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK) {
                                            Map<String, Object> responseBody =
                                                    getResponseBodyAsMap(response);

                                            if (responseBody != null
                                                    && responseBody.containsKey("result")) {
                                                Map<String, Object> result =
                                                        safeCastToMap(responseBody.get("result"));

                                                return CreateWalletResponse.builder()
                                                        .walletUuid(
                                                                (String) result.get("wallet_uuid"))
                                                        .uuid((String) result.get("uuid"))
                                                        .address((String) result.get("address"))
                                                        .network((String) result.get("network"))
                                                        .currency((String) result.get("currency"))
                                                        .url((String) result.get("url"))
                                                        .orderId((String) result.get("order_id"))
                                                        .build();
                                            }
                                        }

                                        throw new RuntimeException(
                                                "Invalid response from Cryptomus API");

                                    } catch (Exception e) {
                                        log.error(
                                                "Failed to create Cryptomus wallet: {}",
                                                e.getMessage(),
                                                e);
                                        throw new RuntimeException("Wallet creation failed", e);
                                    }
                                }));
    }

    /** POST /v1/payment/list - Returns history of created invoices with pagination */
    public PaymentListResponse getPaymentList(
            String dateFrom, String dateTo, Integer page, Integer limit) {
        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    try {
                                        String endpoint = "/payment/list";
                                        String url = apiUrl + endpoint;

                                        Map<String, Object> requestBody = new HashMap<>();
                                        if (dateFrom != null) {
                                            requestBody.put("date_from", dateFrom);
                                        }
                                        if (dateTo != null) {
                                            requestBody.put("date_to", dateTo);
                                        }
                                        if (page != null) {
                                            requestBody.put("page", page);
                                        }
                                        if (limit != null) {
                                            requestBody.put("limit", limit);
                                        }

                                        String jsonPayload =
                                                objectMapper.writeValueAsString(requestBody);
                                        String sign = generateSignature(jsonPayload, false);

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);
                                        headers.set("merchant", merchantId);
                                        headers.set("sign", sign);

                                        HttpEntity<String> entity =
                                                new HttpEntity<>(jsonPayload, headers);

                                        ResponseEntity<Map> response =
                                                restTemplate.exchange(
                                                        url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK
                                                && response.getBody() != null) {
                                            Map<String, Object> responseBody =
                                                    getResponseBodyAsMap(response);
                                            if (responseBody.containsKey("result")) {
                                                Map<String, Object> result =
                                                        safeCastToMap(responseBody.get("result"));

                                                List<Map<String, Object>> items =
                                                        safeCastToList(result.get("items"));
                                                Map<String, Object> paginate =
                                                        safeCastToMap(result.get("paginate"));

                                                return PaymentListResponse.builder()
                                                        .items(items)
                                                        .currentPage(
                                                                (Integer)
                                                                        paginate.get(
                                                                                "current_page"))
                                                        .perPage((Integer) paginate.get("per_page"))
                                                        .total((Integer) paginate.get("total"))
                                                        .build();
                                            }
                                        }

                                        throw new RuntimeException(
                                                "Failed to get payment list from Cryptomus");

                                    } catch (Exception e) {
                                        log.error(
                                                "Failed to get Cryptomus payment list: {}",
                                                e.getMessage(),
                                                e);
                                        throw new RuntimeException(
                                                "Payment list retrieval failed", e);
                                    }
                                }));
    }

    /**
     * GET/POST /v2/user-api/transaction/list - Returns list of transactions Using User API
     * authentication
     */
    public TransactionListResponse getTransactionList(String dateFrom, String dateTo, String type) {
        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    try {
                                        String endpoint = "/user-api/transaction/list";
                                        String url = apiUrlV2 + endpoint;

                                        Map<String, Object> requestBody = new HashMap<>();
                                        if (dateFrom != null) {
                                            requestBody.put("date_from", dateFrom);
                                        }
                                        if (dateTo != null) {
                                            requestBody.put("date_to", dateTo);
                                        }
                                        if (type != null) {
                                            requestBody.put(
                                                    "type", type); // payment, payout, or transfer
                                        }

                                        String jsonPayload =
                                                objectMapper.writeValueAsString(requestBody);
                                        String sign =
                                                generateSignature(
                                                        jsonPayload, true); // Use User API key

                                        HttpHeaders headers = new HttpHeaders();
                                        headers.setContentType(MediaType.APPLICATION_JSON);
                                        headers.set("userId", merchantId); // For User API
                                        headers.set("sign", sign);

                                        HttpEntity<String> entity =
                                                new HttpEntity<>(jsonPayload, headers);

                                        ResponseEntity<Map> response =
                                                restTemplate.exchange(
                                                        url, HttpMethod.POST, entity, Map.class);

                                        if (response.getStatusCode() == HttpStatus.OK
                                                && response.getBody() != null) {
                                            Map<String, Object> responseBody =
                                                    getResponseBodyAsMap(response);
                                            if (responseBody.containsKey("result")) {
                                                Map<String, Object> result =
                                                        safeCastToMap(responseBody.get("result"));

                                                List<Map<String, Object>> items =
                                                        safeCastToList(result.get("items"));

                                                return TransactionListResponse.builder()
                                                        .items(items)
                                                        .build();
                                            }
                                        }

                                        throw new RuntimeException(
                                                "Failed to get transaction list from Cryptomus");

                                    } catch (Exception e) {
                                        log.error(
                                                "Failed to get Cryptomus transaction list: {}",
                                                e.getMessage(),
                                                e);
                                        throw new RuntimeException(
                                                "Transaction list retrieval failed", e);
                                    }
                                }));
    }

    public boolean verifyWebhook(String signature, String data) {
        try {
            String expectedSignature = generateSignature(data, false);
            return signature.equals(expectedSignature);
        } catch (Exception e) {
            log.error("Failed to verify webhook signature: {}", e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeCastToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeCastToList(Object obj) {
        if (obj instanceof List) {
            return (List<Map<String, Object>>) obj;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getResponseBodyAsMap(ResponseEntity<Map> response) {
        return response.getBody();
    }
}

package com.smmpanel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.cryptomus.CreatePaymentRequest;
import com.smmpanel.dto.cryptomus.CreatePaymentResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
// Removed Lombok constructor to use explicit constructor with @Qualifier
public class CryptomusClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    public CryptomusClient(RestTemplate restTemplate, @org.springframework.beans.factory.annotation.Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Value("${app.cryptomus.api.url:https://api.cryptomus.com/v1}")
    private String apiUrl;

    @Value("${app.cryptomus.api.key:your-cryptomus-api-key}")
    private String apiKey;

    @Value("${app.cryptomus.merchant-id:your-merchant-id}")
    private String merchantId;

    public CreatePaymentResponse createPayment(CreatePaymentRequest request) {
        try {
            String endpoint = "/payment";
            String url = apiUrl + endpoint;

            // Add merchant ID to request
            request.setMerchantId(merchantId);

            String jsonPayload = objectMapper.writeValueAsString(request);
            String sign = generateSignature(jsonPayload);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("merchant", merchantId);
            headers.set("sign", sign);

            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

            log.debug("Creating Cryptomus payment: {}", jsonPayload);

            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> responseBody = response.getBody();

                if (responseBody != null && responseBody.containsKey("result")) {
                    Map<String, Object> result = (Map<String, Object>) responseBody.get("result");

                    return CreatePaymentResponse.builder()
                            .uuid((String) result.get("uuid"))
                            .orderId((String) result.get("order_id"))
                            .amount(new BigDecimal(String.valueOf(result.get("amount"))))
                            .currency((String) result.get("currency"))
                            .url((String) result.get("url"))
                            .status((String) result.get("status"))
                            .build();
                }
            }

            throw new RuntimeException("Invalid response from Cryptomus API");

        } catch (Exception e) {
            log.error("Failed to create Cryptomus payment: {}", e.getMessage(), e);
            throw new RuntimeException("Payment creation failed", e);
        }
    }

    public Map<String, Object> getPaymentInfo(String uuid) {
        try {
            String endpoint = "/payment/info";
            String url = apiUrl + endpoint;

            String jsonPayload =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "merchant", merchantId,
                                    "uuid", uuid));

            String sign = generateSignature(jsonPayload);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("merchant", merchantId);
            headers.set("sign", sign);

            HttpEntity<String> entity = new HttpEntity<>(jsonPayload, headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return response.getBody();
            }

            throw new RuntimeException("Failed to get payment info from Cryptomus");

        } catch (Exception e) {
            log.error("Failed to get Cryptomus payment info: {}", e.getMessage(), e);
            throw new RuntimeException("Payment info retrieval failed", e);
        }
    }

    private String generateSignature(String data) {
        try {
            // Create MD5 hash of the data
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            byte[] hashBytes = md5.digest(dataBytes);

            // Convert to base64
            String base64Hash = Base64.getEncoder().encodeToString(hashBytes);

            // Create HMAC-SHA256 signature
            Mac sha256Hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey =
                    new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256Hmac.init(secretKey);

            byte[] signatureBytes = sha256Hmac.doFinal(base64Hash.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signatureBytes);

        } catch (Exception e) {
            log.error("Failed to generate signature: {}", e.getMessage());
            throw new RuntimeException("Signature generation failed", e);
        }
    }

    public boolean verifyWebhook(String signature, String data) {
        try {
            String expectedSignature = generateSignature(data);
            return signature.equals(expectedSignature);
        } catch (Exception e) {
            log.error("Failed to verify webhook signature: {}", e.getMessage());
            return false;
        }
    }
}

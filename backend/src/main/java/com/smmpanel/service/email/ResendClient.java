package com.smmpanel.service.email;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Thin wrapper around the <a href="https://resend.com/docs">Resend transactional email API</a>.
 *
 * <p>This is intentionally a tiny direct-HTTP client (no SDK dependency). The only operation we
 * need is {@code POST /emails} — sending a single transactional message. We swallow
 * provider-specific errors into a domain {@link EmailDeliveryException} so callers don't have to
 * branch on RestTemplate exceptions.
 */
@Slf4j
@Component
public class ResendClient {

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String baseUrl;

    public ResendClient(
            RestTemplateBuilder builder,
            @Value("${app.email.resend.api-key:}") String apiKey,
            @Value("${app.email.resend.base-url:https://api.resend.com}") String baseUrl,
            @Value("${app.email.resend.timeout-ms:8000}") long timeoutMs) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.restTemplate =
                builder.setConnectTimeout(Duration.ofMillis(timeoutMs))
                        .setReadTimeout(Duration.ofMillis(timeoutMs))
                        .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Send a transactional email via Resend.
     *
     * @return Resend message ID (UUID) when accepted by the provider.
     * @throws EmailDeliveryException for any non-2xx response or network failure. The exception
     *     message is safe to log — it never echoes the recipient address or the API key.
     */
    public String send(SendEmailRequest request) {
        if (!isConfigured()) {
            throw new EmailDeliveryException(
                    "Resend API key is not configured (set RESEND_API_KEY).");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<SendEmailResponse> response =
                    restTemplate.exchange(
                            baseUrl + "/emails",
                            HttpMethod.POST,
                            new HttpEntity<>(request, headers),
                            SendEmailResponse.class);
            // Spring 6 returns HttpStatusCode (interface). Don't downcast to HttpStatus —
            // a non-standard upstream code from a CDN/proxy would otherwise CCE here.
            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful() || response.getBody() == null) {
                throw new EmailDeliveryException(
                        "Resend returned non-2xx status: " + status.value());
            }
            log.info(
                    "Resend accepted email subject='{}' messageId={}",
                    truncate(request.getSubject(), 60),
                    response.getBody().getId());
            return response.getBody().getId();
        } catch (RestClientException e) {
            // RestTemplate wraps body in the message; trim aggressively so we never log the API
            // key.
            String trimmed = e.getMessage() == null ? "(no detail)" : truncate(e.getMessage(), 200);
            throw new EmailDeliveryException("Resend request failed: " + trimmed, e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ---------------------------------------------------------------------
    // DTOs (JSON shape required by the Resend API)
    // ---------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SendEmailRequest {
        private String from;
        private List<String> to;
        private String subject;
        private String html;
        private String text;

        @JsonProperty("reply_to")
        private String replyTo;
    }

    @Data
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SendEmailResponse {
        private String id;
    }
}

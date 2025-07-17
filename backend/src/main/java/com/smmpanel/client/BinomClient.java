package com.smmpanel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.CampaignResponse;
import com.smmpanel.dto.binom.CampaignStats;
import com.smmpanel.dto.binom.CreateCampaignRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinomClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.binom.api.url}")
    private String apiUrl;

    @Value("${app.binom.api.key}")
    private String apiKey;

    public CampaignResponse createCampaign(CreateCampaignRequest request) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(apiUrl + "/campaign")
                    .queryParam("api_key", apiKey)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of(
                    "name", request.getName(),
                    "url", request.getUrl(),
                    "traffic_source_id", request.getTrafficSourceId(),
                    "country", request.getCountryCode(),
                    "clicks_limit", request.getClicksLimit(),
                    "daily_limit", request.getDailyLimit(),
                    "status", "active"
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            log.debug("Creating Binom campaign: {}", payload);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                
                return CampaignResponse.builder()
                        .campaignId(String.valueOf(responseBody.get("campaign_id")))
                        .offerId(String.valueOf(responseBody.get("offer_id")))
                        .status(String.valueOf(responseBody.get("status")))
                        .build();
            }

            throw new RuntimeException("Invalid response from Binom API");

        } catch (Exception e) {
            log.error("Failed to create Binom campaign: {}", e.getMessage(), e);
            throw new RuntimeException("Campaign creation failed", e);
        }
    }

    public CampaignStats getCampaignStats(String campaignId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(apiUrl + "/report")
                    .queryParam("api_key", apiKey)
                    .queryParam("format", "json")
                    .queryParam("grouping", "campaign")
                    .queryParam("filters[campaign_id]", campaignId)
                    .queryParam("date_from", java.time.LocalDate.now().toString())
                    .queryParam("date_to", java.time.LocalDate.now().toString())
                    .build()
                    .toUriString();

            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                
                if (responseBody.containsKey("data") && responseBody.get("data") instanceof java.util.List) {
                    java.util.List<Map<String, Object>> data = (java.util.List<Map<String, Object>>) responseBody.get("data");
                    
                    if (!data.isEmpty()) {
                        Map<String, Object> stats = data.get(0);
                        
                        return CampaignStats.builder()
                                .campaignId(campaignId)
                                .clicks(Integer.parseInt(String.valueOf(stats.getOrDefault("clicks", 0))))
                                .conversions(Integer.parseInt(String.valueOf(stats.getOrDefault("conversions", 0))))
                                .spend(Double.parseDouble(String.valueOf(stats.getOrDefault("spend", 0.0))))
                                .revenue(Double.parseDouble(String.valueOf(stats.getOrDefault("revenue", 0.0))))
                                .build();
                    }
                }
                
                // Return empty stats if no data found
                return CampaignStats.builder()
                        .campaignId(campaignId)
                        .clicks(0)
                        .conversions(0)
                        .spend(0.0)
                        .revenue(0.0)
                        .build();
            }

            throw new RuntimeException("Failed to get campaign stats from Binom");

        } catch (Exception e) {
            log.error("Failed to get Binom campaign stats for {}: {}", campaignId, e.getMessage(), e);
            throw new RuntimeException("Stats retrieval failed", e);
        }
    }

    public void stopCampaign(String campaignId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(apiUrl + "/campaign/" + campaignId)
                    .queryParam("api_key", apiKey)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of("status", "paused");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, Map.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                throw new RuntimeException("Failed to stop campaign");
            }

            log.info("Stopped Binom campaign {}", campaignId);

        } catch (Exception e) {
            log.error("Failed to stop Binom campaign {}: {}", campaignId, e.getMessage(), e);
            throw new RuntimeException("Campaign stop failed", e);
        }
    }

    public void resumeCampaign(String campaignId) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(apiUrl + "/campaign/" + campaignId)
                    .queryParam("api_key", apiKey)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of("status", "active");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, Map.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                throw new RuntimeException("Failed to resume campaign");
            }

            log.info("Resumed Binom campaign {}", campaignId);

        } catch (Exception e) {
            log.error("Failed to resume Binom campaign {}: {}", campaignId, e.getMessage(), e);
            throw new RuntimeException("Campaign resume failed", e);
        }
    }

    public void updateCampaignLimit(String campaignId, int newLimit) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(apiUrl + "/campaign/" + campaignId)
                    .queryParam("api_key", apiKey)
                    .queryParam("format", "json")
                    .build()
                    .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> payload = Map.of("clicks_limit", newLimit);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.PUT, entity, Map.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                throw new RuntimeException("Failed to update campaign limit");
            }

            log.info("Updated Binom campaign {} limit to {}", campaignId, newLimit);

        } catch (Exception e) {
            log.error("Failed to update Binom campaign {} limit: {}", campaignId, e.getMessage(), e);
            throw new RuntimeException("Campaign limit update failed", e);
        }
    }
}
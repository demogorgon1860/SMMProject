package com.smmpanel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.*;
import com.smmpanel.exception.BinomApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Official Binom v2 API Client - Refactored to use documented API format Uses /index.php?action=...
 * endpoints as per Binom v2 documentation Maintains backward compatibility while following official
 * API structure
 */
@Slf4j
@Component
public class BinomClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;
    private final io.github.resilience4j.retry.Retry readRetry;
    private final io.github.resilience4j.retry.Retry writeRetry;

    @Value("${app.binom.api.url:https://your-binom-domain.com}")
    private String apiUrl;

    @Value("${app.binom.api.key:your-binom-api-key}")
    private String apiKey;

    public BinomClient(
            @Qualifier("binomRestTemplate") RestTemplate restTemplate,
            @Qualifier("redisObjectMapper") ObjectMapper objectMapper,
            @Qualifier("binomCircuitBreaker") io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker,
            @Qualifier("binomReadRetry") io.github.resilience4j.retry.Retry readRetry,
            @Qualifier("binomWriteRetry") io.github.resilience4j.retry.Retry writeRetry) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.readRetry = readRetry;
        this.writeRetry = writeRetry;
    }

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String FORMAT_JSON = "json";

    // ...existing code...

    // Campaign creation removed - campaigns are pre-configured manually in Binom

    /**
     * Create a new offer in Binom using official v2 API Uses action=offer_create as per
     * documentation
     */
    public CreateOfferResponse createOffer(CreateOfferRequest request) {
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + "/index.php")
                        .queryParam("api_key", apiKey)
                        .queryParam("action", "offer_create")
                        .queryParam("name", request.getName())
                        .queryParam("url", request.getUrl())
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);
                                    headers.set("User-Agent", "SMM-Panel/1.0");
                                    headers.set(API_KEY_HEADER, apiKey);

                                    HttpEntity<CreateOfferRequest> entity =
                                            new HttpEntity<>(request, headers);

                                    log.info("Creating Binom offer: {}", request.getName());

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.POST, entity, Map.class);

                                    // Enhanced response validation
                                    String endpoint = "/index.php?action=offer_create";
                                    validateResponse(response, endpoint, "createOffer");
                                    Map<String, Object> responseBody = response.getBody();
                                    validateResponseBody(responseBody, endpoint, "createOffer");

                                    if (responseBody.containsKey("id")) {
                                        String offerId = String.valueOf(responseBody.get("id"));
                                        log.info(
                                                "Successfully created Binom offer: {} -> {}",
                                                request.getName(),
                                                offerId);

                                        return CreateOfferResponse.builder()
                                                .offerId(offerId)
                                                .name(request.getName())
                                                .url(request.getUrl())
                                                .build();
                                    }

                                    throw new BinomApiException(
                                            "Invalid response: missing id field",
                                            HttpStatus.OK,
                                            endpoint);
                                }));
    }

    /**
     * Assign offer to campaign - Note: In V2, offers are assigned during campaign creation/update
     */
    public AssignOfferResponse assignOfferToCampaign(String campaignId, String offerId) {
        // In Binom V2, offers are assigned via campaign update with paths configuration
        // This is a simplified implementation - proper implementation would update campaign paths
        String endpoint = String.format("/public/api/v1/campaign/%s", campaignId);
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("format", FORMAT_JSON)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);
                                    headers.set("User-Agent", "SMM-Panel/1.0");
                                    headers.set(API_KEY_HEADER, apiKey);

                                    // Need to send campaign update with offer in paths
                                    Map<String, Object> updateRequest = new java.util.HashMap<>();
                                    updateRequest.put("offerId", offerId);

                                    HttpEntity<Map<String, Object>> entity =
                                            new HttpEntity<>(updateRequest, headers);

                                    log.info(
                                            "Assigning offer {} to campaign {}",
                                            offerId,
                                            campaignId);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.PUT, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> responseBody = response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            throw new RuntimeException(
                                                    "Binom API error: "
                                                            + responseBody.get("error"));
                                        }

                                        log.info(
                                                "Successfully assigned offer {} to campaign {}",
                                                offerId,
                                                campaignId);

                                        return AssignOfferResponse.builder()
                                                .campaignId(campaignId)
                                                .offerId(offerId)
                                                .status("ASSIGNED")
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /** Assign offer to all 3 fixed campaigns */
    public AssignOfferResponse assignOfferToFixedCampaigns(String offerId) {
        // The 3 fixed campaign IDs from the database migration
        String[] fixedCampaignIds = {"CAMP_SAMPLE_001", "CAMP_SAMPLE_002", "CAMP_SAMPLE_003"};

        java.util.List<String> successfulAssignments = new java.util.ArrayList<>();
        java.util.List<String> failedAssignments = new java.util.ArrayList<>();

        for (String campaignId : fixedCampaignIds) {
            try {
                AssignOfferResponse response = assignOfferToCampaign(campaignId, offerId);
                if ("ASSIGNED".equals(response.getStatus())) {
                    successfulAssignments.add(campaignId);
                    log.info(
                            "Successfully assigned offer {} to fixed campaign {}",
                            offerId,
                            campaignId);
                } else {
                    failedAssignments.add(campaignId);
                    log.warn(
                            "Failed to assign offer {} to fixed campaign {}: {}",
                            offerId,
                            campaignId,
                            response.getStatus());
                }
            } catch (Exception e) {
                failedAssignments.add(campaignId);
                log.error(
                        "Error assigning offer {} to fixed campaign {}: {}",
                        offerId,
                        campaignId,
                        e.getMessage());
            }
        }

        // Build consolidated response
        String status =
                failedAssignments.isEmpty()
                        ? "ALL_ASSIGNED"
                        : successfulAssignments.isEmpty() ? "ALL_FAILED" : "PARTIAL_ASSIGNED";

        String message =
                String.format(
                        "Assigned to %d/%d fixed campaigns. Success: %s, Failed: %s",
                        successfulAssignments.size(),
                        fixedCampaignIds.length,
                        successfulAssignments,
                        failedAssignments);

        log.info("Fixed campaign assignment summary for offer {}: {}", offerId, message);

        return AssignOfferResponse.builder()
                .campaignId(String.join(",", successfulAssignments))
                .offerId(offerId)
                .status(status)
                .message(message)
                .build();
    }

    /** Check if offer exists */
    public CheckOfferResponse checkOfferExists(String offerName) {
        String endpoint = "/public/api/v1/offer/list/filtered";
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("format", FORMAT_JSON)
                        .queryParam("name", offerName)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                                    headers.set("User-Agent", "SMM-Panel/2.0");

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.GET, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> responseBody = response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            // Offer not found is not an error in this context
                                            return CheckOfferResponse.builder()
                                                    .exists(false)
                                                    .build();
                                        }

                                        if (responseBody.containsKey("offers")
                                                && responseBody.get("offers") instanceof Map) {
                                            Map<String, Object> offers =
                                                    (Map<String, Object>)
                                                            responseBody.get("offers");
                                            if (!offers.isEmpty()) {
                                                String offerId = offers.keySet().iterator().next();
                                                return CheckOfferResponse.builder()
                                                        .exists(true)
                                                        .offerId(offerId)
                                                        .build();
                                            }
                                        }

                                        return CheckOfferResponse.builder().exists(false).build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /**
     * Get campaign statistics using official Binom v2 stats API Uses action=stats endpoint as per
     * documentation
     */
    public CampaignStatsResponse getCampaignStats(String campaignId) {
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + "/index.php")
                        .queryParam("api_key", apiKey)
                        .queryParam("action", "stats")
                        .queryParam("entity_name", "campaign")
                        .queryParam("id[]", campaignId)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                                    headers.set("User-Agent", "SMM-Panel/2.0");

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.GET, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> responseBody = response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            throw new RuntimeException(
                                                    "Binom API error: "
                                                            + responseBody.get("error"));
                                        }

                                        return CampaignStatsResponse.builder()
                                                .campaignId(campaignId)
                                                .clicks(
                                                        Long.valueOf(
                                                                getIntValue(
                                                                        responseBody, "clicks", 0)))
                                                .conversions(
                                                        Long.valueOf(
                                                                getIntValue(
                                                                        responseBody,
                                                                        "conversions",
                                                                        0)))
                                                .cost(
                                                        BigDecimal.valueOf(
                                                                getDoubleValue(
                                                                        responseBody, "cost", 0.0)))
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /** Get all offers list from Binom */
    public OffersListResponse getOffersList() {
        String endpoint = "/public/api/v1/offer/list/all";
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("format", FORMAT_JSON)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                                    headers.set("User-Agent", "SMM-Panel/2.0");

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    log.info("Fetching all offers from Binom");

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.GET, entity, Map.class);

                                    // Enhanced response validation
                                    validateResponse(response, endpoint, "getOffersList");
                                    Map<String, Object> responseBody = response.getBody();
                                    validateResponseBody(responseBody, endpoint, "getOffersList");

                                    List<OffersListResponse.OfferInfo> offers = new ArrayList<>();
                                    int totalCount = 0;

                                    if (responseBody.containsKey("data")) {
                                        Object data = responseBody.get("data");
                                        if (data instanceof List) {
                                            List<?> offersList = (List<?>) data;
                                            totalCount = offersList.size();

                                            for (Object offerObj : offersList) {
                                                if (offerObj instanceof Map) {
                                                    Map<String, Object> offerMap =
                                                            (Map<String, Object>) offerObj;

                                                    OffersListResponse.OfferInfo offer =
                                                            OffersListResponse.OfferInfo.builder()
                                                                    .offerId(
                                                                            getString(
                                                                                    offerMap, "id"))
                                                                    .name(
                                                                            getString(
                                                                                    offerMap,
                                                                                    "name"))
                                                                    .url(getString(offerMap, "url"))
                                                                    .status(
                                                                            getString(
                                                                                    offerMap,
                                                                                    "status"))
                                                                    .type(
                                                                            getString(
                                                                                    offerMap,
                                                                                    "type"))
                                                                    .category(
                                                                            getString(
                                                                                    offerMap,
                                                                                    "category"))
                                                                    .payout(
                                                                            getDoubleValue(
                                                                                    offerMap,
                                                                                    "payout", 0.0))
                                                                    .payoutCurrency(
                                                                            getString(
                                                                                    offerMap,
                                                                                    "payout_currency"))
                                                                    .payoutType(
                                                                            getString(
                                                                                    offerMap,
                                                                                    "payout_type"))
                                                                    .isActive(
                                                                            getBooleanValue(
                                                                                    offerMap,
                                                                                    "is_active",
                                                                                    false))
                                                                    .affiliateNetwork(
                                                                            getString(
                                                                                    offerMap,
                                                                                    "affiliate_network"))
                                                                    .build();

                                                    offers.add(offer);
                                                }
                                            }
                                        }
                                    }

                                    log.info(
                                            "Successfully fetched {} offers from Binom",
                                            totalCount);

                                    return OffersListResponse.builder()
                                            .offers(offers)
                                            .totalCount(totalCount)
                                            .status("SUCCESS")
                                            .message("Offers retrieved successfully")
                                            .build();
                                }));
    }

    /** Update an existing offer in Binom */
    public UpdateOfferResponse updateOffer(String offerId, UpdateOfferRequest request) {
        String endpoint = String.format("/public/api/v1/offer/%s", offerId);
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("format", FORMAT_JSON)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);
                                    headers.set("User-Agent", "SMM-Panel/1.0");
                                    headers.set(API_KEY_HEADER, apiKey);

                                    HttpEntity<UpdateOfferRequest> entity =
                                            new HttpEntity<>(request, headers);

                                    log.info(
                                            "Updating Binom offer: {} with name: {}",
                                            offerId,
                                            request.getName());

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.PUT, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> responseBody = response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            throw new RuntimeException(
                                                    "Binom API error: "
                                                            + responseBody.get("error"));
                                        }

                                        log.info("Successfully updated Binom offer: {}", offerId);

                                        return UpdateOfferResponse.builder()
                                                .offerId(offerId)
                                                .name(request.getName())
                                                .url(request.getUrl())
                                                .status("UPDATED")
                                                .message("Offer updated successfully")
                                                .success(true)
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    // Campaign stop/start removed - campaigns remain active, managed manually in Binom

    /** Get campaign information from Binom */
    public CampaignInfoResponse getCampaignInfo(String campaignId) {
        String endpoint = "/public/api/v1/info/campaign";
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("format", FORMAT_JSON)
                        .queryParam("id[]", campaignId)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                                    headers.set("User-Agent", "SMM-Panel/2.0");

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    log.info("Fetching campaign info for: {}", campaignId);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.GET, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> responseBody = response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            throw new RuntimeException(
                                                    "Binom API error: "
                                                            + responseBody.get("error"));
                                        }

                                        // Build campaign stats
                                        CampaignInfoResponse.CampaignStats stats =
                                                CampaignInfoResponse.CampaignStats.builder()
                                                        .clicks(
                                                                Long.valueOf(
                                                                        getIntValue(
                                                                                responseBody,
                                                                                "clicks",
                                                                                0)))
                                                        .conversions(
                                                                Long.valueOf(
                                                                        getIntValue(
                                                                                responseBody,
                                                                                "conversions",
                                                                                0)))
                                                        .cost(
                                                                getDoubleValue(
                                                                        responseBody, "cost", 0.0))
                                                        .revenue(
                                                                getDoubleValue(
                                                                        responseBody,
                                                                        "revenue",
                                                                        0.0))
                                                        .roi(
                                                                getDoubleValue(
                                                                        responseBody, "roi", 0.0))
                                                        .ctr(
                                                                getDoubleValue(
                                                                        responseBody, "ctr", 0.0))
                                                        .cr(getDoubleValue(responseBody, "cr", 0.0))
                                                        .build();

                                        log.info(
                                                "Successfully fetched campaign info for: {}",
                                                campaignId);

                                        return CampaignInfoResponse.builder()
                                                .campaignId(campaignId)
                                                .name(getString(responseBody, "name"))
                                                .status(getString(responseBody, "status"))
                                                .trafficSource(
                                                        getString(responseBody, "traffic_source"))
                                                .landingPage(
                                                        getString(responseBody, "landing_page"))
                                                .costModel(getString(responseBody, "cost_model"))
                                                .costValue(
                                                        getDoubleValue(
                                                                responseBody, "cost_value", 0.0))
                                                .geoTargeting(
                                                        getString(responseBody, "geo_targeting"))
                                                .isActive(
                                                        getBooleanValue(
                                                                responseBody, "is_active", false))
                                                .createdAt(getString(responseBody, "created_at"))
                                                .updatedAt(getString(responseBody, "updated_at"))
                                                .stats(stats)
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /** Set click cost for a campaign in Binom */
    public SetClickCostResponse setClickCost(SetClickCostRequest request) {
        String endpoint =
                String.format("/public/api/v1/clicks/campaign/%s", request.getCampaignId());
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("format", FORMAT_JSON)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);
                                    headers.set("User-Agent", "SMM-Panel/1.0");
                                    headers.set(API_KEY_HEADER, apiKey);

                                    HttpEntity<SetClickCostRequest> entity =
                                            new HttpEntity<>(request, headers);

                                    log.info(
                                            "Setting click cost for campaign: {} to {} {}",
                                            request.getCampaignId(),
                                            request.getCost(),
                                            request.getCurrency());

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.PUT, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> responseBody = response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            throw new RuntimeException(
                                                    "Binom API error: "
                                                            + responseBody.get("error"));
                                        }

                                        log.info(
                                                "Successfully set click cost for campaign: {}",
                                                request.getCampaignId());

                                        return SetClickCostResponse.builder()
                                                .campaignId(request.getCampaignId())
                                                .cost(request.getCost())
                                                .costModel(request.getCostModel())
                                                .currency(request.getCurrency())
                                                .status("SUCCESS")
                                                .message("Click cost set successfully")
                                                .success(true)
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /** Check if a campaign exists in Binom */
    public boolean campaignExists(String campaignId) {
        try {
            String endpoint = String.format("/public/api/v1/campaign/%s", campaignId);
            String url =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                            .queryParam("format", FORMAT_JSON)
                            .build()
                            .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set(API_KEY_HEADER, apiKey);
            headers.set("User-Agent", "SMM-Panel/1.0");
            HttpEntity<String> entity = new HttpEntity<>("", headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            return response.getStatusCode() == HttpStatus.OK;
        } catch (Exception e) {
            log.debug("Campaign {} does not exist: {}", campaignId, e.getMessage());
            return false;
        }
    }

    /**
     * Enhanced response validation with comprehensive HTTP status code handling Handles: 400 (Bad
     * Request), 401 (Unauthorized), 403 (Forbidden), 404 (Not Found), 418 (I'm a teapot)
     */
    private void validateResponse(ResponseEntity<Map> response, String endpoint, String operation) {
        HttpStatus status = HttpStatus.valueOf(response.getStatusCode().value());

        // Log response status for debugging
        log.debug(
                "Binom API response for {}: {} - {}",
                operation,
                status.value(),
                status.getReasonPhrase());

        if (status.is2xxSuccessful()) {
            // Success - continue with normal processing
            return;
        }

        // Handle specific error status codes
        String errorMessage = buildErrorMessage(status, response.getBody(), endpoint, operation);

        switch (status) {
            case BAD_REQUEST: // 400
                log.error("Bad request to Binom API: {} - {}", endpoint, errorMessage);
                throw new BinomApiException(
                        "Bad request: " + errorMessage,
                        status,
                        extractBinomErrorCode(response.getBody()),
                        endpoint);

            case UNAUTHORIZED: // 401
                log.error("Unauthorized access to Binom API: {} - Check API key", endpoint);
                throw new BinomApiException(
                        "Unauthorized: Invalid or expired API key - " + errorMessage,
                        status,
                        extractBinomErrorCode(response.getBody()),
                        endpoint);

            case FORBIDDEN: // 403
                log.error("Forbidden access to Binom API: {} - Insufficient permissions", endpoint);
                throw new BinomApiException(
                        "Forbidden: Insufficient permissions for operation - " + errorMessage,
                        status,
                        extractBinomErrorCode(response.getBody()),
                        endpoint);

            case NOT_FOUND: // 404
                log.warn("Resource not found in Binom API: {} - {}", endpoint, errorMessage);
                throw new BinomApiException(
                        "Not found: " + errorMessage,
                        status,
                        extractBinomErrorCode(response.getBody()),
                        endpoint);

            case I_AM_A_TEAPOT: // 418
                log.warn(
                        "Binom API returned teapot status: {} - Rate limiting or maintenance",
                        endpoint);
                throw new BinomApiException(
                        "Service temporarily unavailable (teapot response): " + errorMessage,
                        status,
                        extractBinomErrorCode(response.getBody()),
                        endpoint);

            case TOO_MANY_REQUESTS: // 429
                log.warn("Rate limit exceeded for Binom API: {} - {}", endpoint, errorMessage);
                throw new BinomApiException(
                        "Rate limit exceeded: " + errorMessage,
                        status,
                        extractBinomErrorCode(response.getBody()),
                        endpoint);

            default:
                if (status.is4xxClientError()) {
                    log.error(
                            "Client error from Binom API: {} {} - {}",
                            status.value(),
                            endpoint,
                            errorMessage);
                    throw new BinomApiException(
                            "Client error (" + status.value() + "): " + errorMessage,
                            status,
                            extractBinomErrorCode(response.getBody()),
                            endpoint);
                } else if (status.is5xxServerError()) {
                    log.error(
                            "Server error from Binom API: {} {} - {}",
                            status.value(),
                            endpoint,
                            errorMessage);
                    throw new BinomApiException(
                            "Server error (" + status.value() + "): " + errorMessage,
                            status,
                            extractBinomErrorCode(response.getBody()),
                            endpoint);
                } else {
                    log.error(
                            "Unexpected status from Binom API: {} {} - {}",
                            status.value(),
                            endpoint,
                            errorMessage);
                    throw new BinomApiException(
                            "Unexpected response (" + status.value() + "): " + errorMessage,
                            status,
                            extractBinomErrorCode(response.getBody()),
                            endpoint);
                }
        }
    }

    /** Build comprehensive error message from response */
    private String buildErrorMessage(
            HttpStatus status,
            Map<String, Object> responseBody,
            String endpoint,
            String operation) {
        StringBuilder message = new StringBuilder();
        message.append("Operation '").append(operation).append("' failed");

        if (responseBody != null) {
            // Check for Binom-specific error fields
            if (responseBody.containsKey("error")) {
                message.append(" - API Error: ").append(responseBody.get("error"));
            }
            if (responseBody.containsKey("message")) {
                message.append(" - Message: ").append(responseBody.get("message"));
            }
            if (responseBody.containsKey("error_code")) {
                message.append(" - Error Code: ").append(responseBody.get("error_code"));
            }
            if (responseBody.containsKey("details")) {
                message.append(" - Details: ").append(responseBody.get("details"));
            }
        } else {
            message.append(" - No response body");
        }

        return message.toString();
    }

    /** Extract Binom-specific error code from response body */
    private String extractBinomErrorCode(Map<String, Object> responseBody) {
        if (responseBody == null) return null;

        Object errorCode = responseBody.get("error_code");
        if (errorCode != null) return errorCode.toString();

        Object code = responseBody.get("code");
        if (code != null) return code.toString();

        return null;
    }

    /** Enhanced response body validation with better error messages */
    private void validateResponseBody(
            Map<String, Object> responseBody, String endpoint, String operation) {
        if (responseBody == null) {
            throw new BinomApiException(
                    "Empty response body from " + operation + " operation", null, endpoint);
        }

        // Check for API-level errors in successful HTTP responses
        if (responseBody.containsKey("error")) {
            String error = responseBody.get("error").toString();
            String errorCode = extractBinomErrorCode(responseBody);

            log.warn(
                    "Binom API error in response body for {}: {} (code: {})",
                    operation,
                    error,
                    errorCode);

            throw new BinomApiException(
                    "API Error: " + error,
                    HttpStatus.OK, // HTTP was successful but API returned error
                    errorCode,
                    endpoint);
        }

        // Log successful response for debugging
        log.debug("Valid response body received for {} operation on {}", operation, endpoint);
    }

    /** Test connection to Binom API */
    private int getIntValue(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double getDoubleValue(Map<String, Object> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean getBooleanValue(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) {
            String strValue = value.toString().toLowerCase();
            return "true".equals(strValue) || "1".equals(strValue) || "yes".equals(strValue);
        }
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return defaultValue;
    }

    public CheckOfferResponse checkOffer(String offerName) {
        String endpoint = "/public/api/v1/offer/list/filtered";
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("format", FORMAT_JSON)
                        .queryParam("name", offerName)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                                    headers.set("User-Agent", "SMM-Panel/2.0");

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    log.info("Checking Binom offer: {}", offerName);

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.GET, entity, Map.class);

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> responseBody = response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            // Offer not found is not an error in this context
                                            return CheckOfferResponse.builder()
                                                    .exists(false)
                                                    .message("Offer not found")
                                                    .build();
                                        }

                                        if (responseBody.containsKey("data")) {
                                            Object data = responseBody.get("data");
                                            if (data instanceof java.util.List) {
                                                java.util.List<?> offers = (java.util.List<?>) data;
                                                if (!offers.isEmpty()
                                                        && offers.get(0) instanceof Map) {
                                                    Map<String, Object> offer =
                                                            (Map<String, Object>) offers.get(0);
                                                    String offerId =
                                                            String.valueOf(offer.get("id"));
                                                    return CheckOfferResponse.builder()
                                                            .exists(true)
                                                            .offerId(offerId)
                                                            .message("Offer found")
                                                            .build();
                                                }
                                            }
                                        }

                                        return CheckOfferResponse.builder()
                                                .exists(false)
                                                .message("Offer not found")
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /**
     * Update campaign status (pause/resume) using official Binom v2 campaign_update API Action:
     * campaign_update
     */
    public boolean updateCampaignStatus(String campaignId, String status) {
        try {
            String url =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/index.php")
                            .queryParam("api_key", apiKey)
                            .queryParam("action", "campaign_update")
                            .queryParam("id", campaignId)
                            .queryParam("status", status)
                            .build()
                            .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", "SMM-Panel/2.0");

            HttpEntity<String> entity = new HttpEntity<>("", headers);

            log.info("Updating campaign {} status to: {}", campaignId, status);

            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (!body.containsKey("error")) {
                    log.info("Successfully updated campaign {} status to {}", campaignId, status);
                    return true;
                }
            }

            return false;
        } catch (Exception e) {
            log.error("Failed to update campaign {} status: {}", campaignId, e.getMessage());
            return false;
        }
    }

    /** Pause campaign - convenience method */
    public boolean pauseCampaign(String campaignId) {
        return updateCampaignStatus(campaignId, "paused");
    }

    /** Resume campaign - convenience method */
    public boolean resumeCampaign(String campaignId) {
        return updateCampaignStatus(campaignId, "active");
    }

    /** Get campaigns list using official Binom v2 campaigns API Action: campaigns */
    public Map<String, Object> getCampaigns(List<String> campaignIds) {
        try {
            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/index.php")
                            .queryParam("api_key", apiKey)
                            .queryParam("action", "campaigns");

            if (campaignIds != null && !campaignIds.isEmpty()) {
                for (String id : campaignIds) {
                    builder.queryParam("id[]", id);
                }
            }

            String url = builder.build().toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", "SMM-Panel/2.0");

            HttpEntity<String> entity = new HttpEntity<>("", headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            return new HashMap<>();
        } catch (Exception e) {
            log.error("Failed to fetch campaigns: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Get detailed stats with cost and views from Binom Uses official stats API with extended
     * parameters
     */
    public CampaignStatsResponse getDetailedStats(
            String campaignId, String dateFrom, String dateTo) {
        try {
            String url =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/index.php")
                            .queryParam("api_key", apiKey)
                            .queryParam("action", "stats")
                            .queryParam("entity_name", "campaign")
                            .queryParam("id[]", campaignId)
                            .queryParam("date_from", dateFrom != null ? dateFrom : "")
                            .queryParam("date_to", dateTo != null ? dateTo : "")
                            .build()
                            .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("User-Agent", "SMM-Panel/2.0");

            HttpEntity<String> entity = new HttpEntity<>("", headers);

            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                // Parse the response according to Binom v2 stats format
                if (body.containsKey("data")) {
                    Map<String, Object> data = (Map<String, Object>) body.get("data");
                    if (data.containsKey(campaignId)) {
                        Map<String, Object> campaignData =
                                (Map<String, Object>) data.get(campaignId);

                        return CampaignStatsResponse.builder()
                                .campaignId(campaignId)
                                .clicks(getLongValue(campaignData, "clicks", 0L))
                                .conversions(getLongValue(campaignData, "conversions", 0L))
                                .cost(BigDecimal.valueOf(getDoubleValue(campaignData, "cost", 0.0)))
                                .revenue(
                                        BigDecimal.valueOf(
                                                getDoubleValue(campaignData, "revenue", 0.0)))
                                .status("ACTIVE")
                                .build();
                    }
                }
            }

            // Return empty stats if not found
            return CampaignStatsResponse.builder()
                    .campaignId(campaignId)
                    .clicks(0L)
                    .conversions(0L)
                    .cost(BigDecimal.ZERO)
                    .revenue(BigDecimal.ZERO)
                    .status("NO_DATA")
                    .build();

        } catch (Exception e) {
            log.error(
                    "Failed to get detailed stats for campaign {}: {}", campaignId, e.getMessage());
            throw new BinomApiException("Failed to get campaign stats", e);
        }
    }

    private Long getLongValue(Map<String, Object> map, String key, Long defaultValue) {
        Object value = map.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

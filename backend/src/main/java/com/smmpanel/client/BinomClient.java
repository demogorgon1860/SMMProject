package com.smmpanel.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.*;
import com.smmpanel.exception.BinomApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    @Value("${app.binom.api.url}")
    private String apiUrl;

    @Value("${app.binom.api.key}")
    private String apiKey;

    @Value("${app.binom.api.username:}")
    private String username;

    @Value("${app.binom.api.password:}")
    private String password;

    @Value("${app.binom.tracking.url:}")
    private String trackingUrl; // For click tracking

    // Per-campaign locks to prevent race conditions when multiple orders update the same campaign
    // Key: campaignId, Value: ReentrantLock for that campaign
    private final ConcurrentHashMap<String, ReentrantLock> campaignLocks =
            new ConcurrentHashMap<>();

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

    private static final String API_KEY_HEADER = "api-key"; // Binom uses lowercase api-key header
    private static final String FORMAT_JSON = "json";

    /** Create HTTP headers with authentication */
    private HttpHeaders createAuthHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "SMM-Panel/2.0");

        // Add API key header
        headers.set(API_KEY_HEADER, apiKey); // Using lowercase "api-key" header

        // Add Basic Authentication if username and password are provided
        // Binom typically requires both API key AND account credentials
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            String auth = username + ":" + password;
            byte[] encodedAuth =
                    java.util.Base64.getEncoder()
                            .encode(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String authHeader = "Basic " + new String(encodedAuth);
            headers.set("Authorization", authHeader);
            log.debug("Added Basic Authentication header for user: {}", username);
        } else {
            log.warn(
                    "Binom username/password not configured - authentication may fail. Set"
                            + " BINOM_USERNAME and BINOM_PASSWORD environment variables.");
        }

        return headers;
    }

    // ...existing code...

    // Campaign creation removed - campaigns are pre-configured manually in Binom

    /**
     * Create a new offer in Binom using the working format from the script Uses the exact structure
     * that has been proven to work
     */
    public CreateOfferResponse createOffer(CreateOfferRequest request) {
        String endpoint = "/public/api/v1/offer";
        String url = apiUrl + endpoint;

        return circuitBreaker.executeSupplier(
                () ->
                        writeRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = new HttpHeaders();
                                    headers.setContentType(MediaType.APPLICATION_JSON);
                                    headers.set("User-Agent", "SMM-Panel/2.0");
                                    headers.set(API_KEY_HEADER, apiKey);

                                    // Build request body matching the working script format
                                    Map<String, Object> offerData = new HashMap<>();
                                    offerData.put("name", request.getName());
                                    offerData.put("url", request.getUrl());
                                    offerData.put("countryCode", "GLOBAL"); // Changed to uppercase
                                    // Removed affiliateNetworkId - not required and no networks
                                    // exist
                                    offerData.put(
                                            "amount",
                                            request.getPayout() != null
                                                    ? request.getPayout()
                                                    : 0.0); // Use request payout if available
                                    offerData.put(
                                            "currency",
                                            request.getPayoutCurrency() != null
                                                    ? request.getPayoutCurrency()
                                                    : "USD");
                                    offerData.put(
                                            "isAuto",
                                            true); // Auto mode for automatic payout calculation
                                    offerData.put("isUpsell", false);

                                    Map<String, Object> requestBody = new HashMap<>();
                                    requestBody.put("offer", offerData);

                                    HttpEntity<Map<String, Object>> entity =
                                            new HttpEntity<>(requestBody, headers);

                                    log.info("Creating Binom offer: {}", request.getName());

                                    ResponseEntity<Map> response =
                                            restTemplate.exchange(
                                                    url, HttpMethod.POST, entity, Map.class);

                                    // Enhanced response validation
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> responseBody =
                                            (Map<String, Object>) response.getBody();
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
     * Assign offer to campaign - follows the working script pattern Gets campaign, adds offer to
     * paths, updates campaign SYNCHRONIZED: Uses per-campaign locks to prevent race conditions when
     * multiple orders try to update the same campaign simultaneously
     */
    public AssignOfferResponse assignOfferToCampaign(String campaignId, String offerId) {
        // CRITICAL: Acquire lock for this specific campaign to prevent concurrent modifications
        // This prevents the lost update problem when multiple orders assign offers simultaneously
        ReentrantLock lock = campaignLocks.computeIfAbsent(campaignId, k -> new ReentrantLock());

        lock.lock();
        try {
            log.debug(
                    "Acquired lock for campaign {} to assign offer {} (thread: {})",
                    campaignId,
                    offerId,
                    Thread.currentThread().getName());

            String endpoint = String.format("/public/api/v1/campaign/%s", campaignId);
            String url = apiUrl + endpoint;

            return circuitBreaker.executeSupplier(
                    () ->
                            writeRetry.executeSupplier(
                                    () -> {
                                        // First, get the existing campaign
                                        HttpHeaders headers = createAuthHeaders();
                                        HttpEntity<String> getEntity =
                                                new HttpEntity<>("", headers);

                                        ResponseEntity<Map> getResponse =
                                                restTemplate.exchange(
                                                        url, HttpMethod.GET, getEntity, Map.class);

                                        if (!getResponse.getStatusCode().is2xxSuccessful()
                                                || getResponse.getBody() == null) {
                                            throw new BinomApiException(
                                                    "Failed to get campaign " + campaignId);
                                        }

                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> campaign =
                                                (Map<String, Object>) getResponse.getBody();

                                        // Extract existing offers from campaign
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> customRotation =
                                                (Map<String, Object>)
                                                        campaign.get("customRotation");
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> defaultPaths =
                                                castToListOfMaps(
                                                        customRotation.get("defaultPaths"));
                                        Map<String, Object> path = defaultPaths.get(0);
                                        @SuppressWarnings("unchecked")
                                        List<Map<String, Object>> existingOffers =
                                                castToListOfMaps(
                                                        path.getOrDefault(
                                                                "offers", new ArrayList<>()));

                                        // Check if offer already exists
                                        for (Map<String, Object> offer : existingOffers) {
                                            if (String.valueOf(offer.get("offerId"))
                                                    .equals(offerId)) {
                                                log.info(
                                                        "Offer {} already exists in campaign {}",
                                                        offerId,
                                                        campaignId);
                                                return AssignOfferResponse.builder()
                                                        .campaignId(campaignId)
                                                        .offerId(offerId)
                                                        .status("ALREADY_ASSIGNED")
                                                        .build();
                                            }
                                        }

                                        // Create new offer structure (matching script)
                                        Map<String, Object> newOffer = new HashMap<>();
                                        newOffer.put("offerId", Integer.parseInt(offerId));
                                        newOffer.put("campaignId", 0);
                                        newOffer.put("directUrl", ""); // CRITICAL: Must be present
                                        newOffer.put("enabled", true);
                                        newOffer.put("weight", 100);

                                        // Add new offer to the list
                                        List<Map<String, Object>> updatedOffers =
                                                new ArrayList<>(existingOffers);
                                        updatedOffers.add(newOffer);

                                        // Build update payload (matching Binom API documentation)
                                        Map<String, Object> updatePayload = new HashMap<>();
                                        updatePayload.put("name", campaign.get("name"));
                                        updatePayload.put(
                                                "key", campaign.get("key")); // Add key field
                                        updatePayload.put(
                                                "groupUuid",
                                                campaign.get("groupUuid")); // Add groupUuid
                                        updatePayload.put(
                                                "trafficSourceId", campaign.get("trafficSourceId"));

                                        // Extract cost information properly
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> cost =
                                                (Map<String, Object>)
                                                        campaign.getOrDefault(
                                                                "cost", new HashMap<>());
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> money =
                                                (Map<String, Object>)
                                                        cost.getOrDefault("money", new HashMap<>());
                                        updatePayload.put(
                                                "costModel", cost.getOrDefault("model", "CPC"));
                                        updatePayload.put(
                                                "amount", money.getOrDefault("amount", 0));
                                        updatePayload.put(
                                                "currency", money.getOrDefault("currency", "USD"));
                                        updatePayload.put(
                                                "isAuto", cost.getOrDefault("isAuto", false));

                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> hideReferrer =
                                                (Map<String, Object>) campaign.get("hideReferrer");
                                        updatePayload.put(
                                                "hideReferrerType", hideReferrer.get("type"));
                                        updatePayload.put("domainUuid", campaign.get("domainUuid"));
                                        updatePayload.put(
                                                "distributionType",
                                                campaign.getOrDefault(
                                                        "distributionType", "NORMAL"));

                                        // Add rotationId if present
                                        if (campaign.containsKey("rotationId")) {
                                            updatePayload.put(
                                                    "rotationId", campaign.get("rotationId"));
                                        }

                                        // Add campaignSettings (REQUIRED by API - must always be
                                        // present)
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> campaignSettings =
                                                (Map<String, Object>)
                                                        campaign.getOrDefault(
                                                                "campaignSettings",
                                                                new HashMap<>());
                                        // Ensure all required fields are present with defaults
                                        campaignSettings.putIfAbsent("s2sPostback", "");
                                        campaignSettings.putIfAbsent("ea", null);
                                        campaignSettings.putIfAbsent("postbackPercent", 100);
                                        campaignSettings.putIfAbsent("payoutPercent", 100);
                                        campaignSettings.putIfAbsent("trafficLossPercent", 0);
                                        campaignSettings.putIfAbsent("appendToCampaignUrl", "");
                                        campaignSettings.putIfAbsent("appendToOfferUrl", "");
                                        campaignSettings.putIfAbsent("appendToLandingUrl", "");
                                        updatePayload.put("campaignSettings", campaignSettings);

                                        // Build customRotation with updated offers
                                        Map<String, Object> updatedPath = new HashMap<>();
                                        updatedPath.put("id", path.getOrDefault("id", 1));
                                        updatedPath.put(
                                                "name", path.getOrDefault("name", "Path 1"));
                                        updatedPath.put(
                                                "enabled", path.getOrDefault("enabled", true));
                                        updatedPath.put("weight", path.getOrDefault("weight", 100));
                                        updatedPath.put(
                                                "landings",
                                                path.getOrDefault("landings", new ArrayList<>()));
                                        updatedPath.put("offers", updatedOffers);

                                        Map<String, Object> updatedCustomRotation = new HashMap<>();
                                        updatedCustomRotation.put(
                                                "defaultPaths", List.of(updatedPath));
                                        updatedCustomRotation.put(
                                                "rules",
                                                customRotation.getOrDefault(
                                                        "rules", new ArrayList<>()));
                                        updatePayload.put("customRotation", updatedCustomRotation);

                                        // Send update request
                                        HttpEntity<Map<String, Object>> updateEntity =
                                                new HttpEntity<>(updatePayload, headers);

                                        log.info(
                                                "Updating campaign {} to add offer {}",
                                                campaignId,
                                                offerId);

                                        ResponseEntity<Map> updateResponse =
                                                restTemplate.exchange(
                                                        url,
                                                        HttpMethod.PUT,
                                                        updateEntity,
                                                        Map.class);

                                        if (updateResponse.getStatusCode() == HttpStatus.OK) {
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

                                        throw new BinomApiException(
                                                "Failed to update campaign: "
                                                        + updateResponse.getBody());
                                    }));
        } finally {
            lock.unlock();
            log.debug(
                    "Released lock for campaign {} after assigning offer {} (thread: {})",
                    campaignId,
                    offerId,
                    Thread.currentThread().getName());
        }
    }

    /**
     * Assign offer to specified campaigns
     *
     * @param offerId The offer ID to assign
     * @param campaignIds List of campaign IDs to assign the offer to
     * @return AssignOfferResponse with the result of the assignment
     */
    public AssignOfferResponse assignOfferToCampaigns(String offerId, List<String> campaignIds) {
        if (campaignIds == null || campaignIds.isEmpty()) {
            log.warn("No campaign IDs provided for offer assignment");
            return AssignOfferResponse.builder()
                    .offerId(offerId)
                    .status("NO_CAMPAIGNS")
                    .message("No campaigns provided for assignment")
                    .success(false)
                    .build();
        }

        java.util.List<String> successfulAssignments = new java.util.ArrayList<>();
        java.util.List<String> failedAssignments = new java.util.ArrayList<>();

        for (String campaignId : campaignIds) {
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
                        "Assigned to %d/%d campaigns. Success: %s, Failed: %s",
                        successfulAssignments.size(),
                        campaignIds.size(),
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
        String endpoint = "/public/api/v1/info/offer";
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("name", offerName)
                        .queryParam("limit", 10)
                        .queryParam("datePreset", "all_time")
                        .queryParam("timezone", "UTC")
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = createAuthHeaders();

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    ResponseEntity<List<Map<String, Object>>> response =
                                            restTemplate.exchange(
                                                    url,
                                                    HttpMethod.GET,
                                                    entity,
                                                    new ParameterizedTypeReference<
                                                            List<Map<String, Object>>>() {});

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        List<Map<String, Object>> offers = response.getBody();

                                        // Filter out totals row and check if offer exists
                                        offers =
                                                offers.stream()
                                                        .filter(o -> !"totals".equals(o.get("id")))
                                                        .filter(
                                                                o ->
                                                                        offerName.equalsIgnoreCase(
                                                                                getString(
                                                                                        o, "name")))
                                                        .collect(Collectors.toList());

                                        if (!offers.isEmpty()) {
                                            Map<String, Object> offer = offers.get(0);
                                            String offerId = String.valueOf(offer.get("id"));
                                            return CheckOfferResponse.builder()
                                                    .exists(true)
                                                    .offerId(offerId)
                                                    .build();
                                        }

                                        return CheckOfferResponse.builder().exists(false).build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /**
     * Get campaign statistics using official Binom v2 stats API Uses /public/api/v1/stats/campaign
     * endpoint as per documentation
     */
    public CampaignStatsResponse getCampaignStats(String campaignId) {
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + "/public/api/v1/stats/campaign")
                        .queryParam("datePreset", "all_time")
                        .queryParam("timezone", "UTC")
                        .queryParam("sortColumn", "clicks")
                        .queryParam("sortType", "desc")
                        .queryParam("limit", 100)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = createAuthHeaders();

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    ResponseEntity<Map<String, Object>> response =
                                            restTemplate.exchange(
                                                    url,
                                                    HttpMethod.GET,
                                                    entity,
                                                    new ParameterizedTypeReference<
                                                            Map<String, Object>>() {});

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> responseBody =
                                                (Map<String, Object>) response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            throw new RuntimeException(
                                                    "Binom API error: "
                                                            + responseBody.get("error"));
                                        }

                                        // Parse stats from response
                                        if (responseBody.containsKey("stats")
                                                && responseBody.get("stats") instanceof List) {
                                            @SuppressWarnings("unchecked")
                                            List<Map<String, Object>> stats =
                                                    castToListOfMaps(responseBody.get("stats"));
                                            // Find campaign in stats or aggregate all if no
                                            // specific ID
                                            long totalClicks = 0;
                                            long totalConversions = 0;
                                            BigDecimal totalCost = BigDecimal.ZERO;
                                            BigDecimal totalRevenue = BigDecimal.ZERO;

                                            for (Map<String, Object> stat : stats) {
                                                // If we have a specific campaign ID, only process
                                                // that one
                                                if (stat.containsKey("id")
                                                        && String.valueOf(stat.get("id"))
                                                                .equals(campaignId)) {
                                                    return CampaignStatsResponse.builder()
                                                            .campaignId(campaignId)
                                                            .clicks(
                                                                    getLongValue(
                                                                            stat, "clicks", 0L))
                                                            .conversions(
                                                                    getLongValue(stat, "leads", 0L))
                                                            .cost(
                                                                    BigDecimal.valueOf(
                                                                            getDoubleValue(
                                                                                    stat, "costs",
                                                                                    0.0)))
                                                            .revenue(
                                                                    BigDecimal.valueOf(
                                                                            getDoubleValue(
                                                                                    stat, "revenue",
                                                                                    0.0)))
                                                            .build();
                                                }
                                                // Otherwise aggregate all campaigns
                                                totalClicks += getLongValue(stat, "clicks", 0L);
                                                totalConversions += getLongValue(stat, "leads", 0L);
                                                totalCost =
                                                        totalCost.add(
                                                                BigDecimal.valueOf(
                                                                        getDoubleValue(
                                                                                stat, "costs",
                                                                                0.0)));
                                                totalRevenue =
                                                        totalRevenue.add(
                                                                BigDecimal.valueOf(
                                                                        getDoubleValue(
                                                                                stat, "revenue",
                                                                                0.0)));
                                            }

                                            // Return aggregated stats if we processed any
                                            if (!stats.isEmpty()) {
                                                return CampaignStatsResponse.builder()
                                                        .campaignId(campaignId)
                                                        .clicks(totalClicks)
                                                        .conversions(totalConversions)
                                                        .cost(totalCost)
                                                        .revenue(totalRevenue)
                                                        .build();
                                            }
                                        }

                                        // Return empty stats if nothing found
                                        return CampaignStatsResponse.builder()
                                                .campaignId(campaignId)
                                                .clicks(0L)
                                                .conversions(0L)
                                                .cost(BigDecimal.ZERO)
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /** Get all offers list from Binom */
    public OffersListResponse getOffersList() {
        String endpoint = "/public/api/v1/info/offer";
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("datePreset", "all_time")
                        .queryParam("timezone", "UTC")
                        .queryParam("limit", 1000)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = createAuthHeaders();

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    log.info("Fetching all offers from Binom");

                                    ResponseEntity<List<Map<String, Object>>> response =
                                            restTemplate.exchange(
                                                    url,
                                                    HttpMethod.GET,
                                                    entity,
                                                    new ParameterizedTypeReference<
                                                            List<Map<String, Object>>>() {});

                                    // Enhanced response validation
                                    if (!response.getStatusCode().is2xxSuccessful()
                                            || response.getBody() == null) {
                                        throw new RuntimeException(
                                                "Failed to get offers from Binom");
                                    }

                                    List<Map<String, Object>> responseBody = response.getBody();
                                    List<OffersListResponse.OfferInfo> offers = new ArrayList<>();

                                    // Filter out totals row and process offers
                                    List<Map<String, Object>> offersList =
                                            responseBody.stream()
                                                    .filter(o -> !"totals".equals(o.get("id")))
                                                    .collect(Collectors.toList());

                                    int totalCount = offersList.size();

                                    for (Map<String, Object> offerMap : offersList) {

                                        OffersListResponse.OfferInfo offer =
                                                OffersListResponse.OfferInfo.builder()
                                                        .offerId(getString(offerMap, "id"))
                                                        .name(getString(offerMap, "name"))
                                                        .url(getString(offerMap, "url"))
                                                        .status(getString(offerMap, "status"))
                                                        .type(getString(offerMap, "type"))
                                                        .category(getString(offerMap, "category"))
                                                        .payout(
                                                                getDoubleValue(
                                                                        offerMap, "payout", 0.0))
                                                        .payoutCurrency(
                                                                getString(
                                                                        offerMap,
                                                                        "payout_currency"))
                                                        .payoutType(
                                                                getString(offerMap, "payout_type"))
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
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> responseBody =
                                                (Map<String, Object>) response.getBody();

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
    @SuppressWarnings("unchecked")
    public CampaignInfoResponse getCampaignInfo(String campaignId) {
        String endpoint = "/public/api/v1/info/campaign";
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("name", "")
                        .queryParam("datePreset", "all_time")
                        .queryParam("timezone", "UTC")
                        .queryParam("sortColumn", "clicks")
                        .queryParam("sortType", "desc")
                        .queryParam("limit", 100)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = createAuthHeaders();

                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    log.info("Fetching campaign info for: {}", campaignId);

                                    ResponseEntity<Map<String, Object>> response =
                                            restTemplate.exchange(
                                                    url,
                                                    HttpMethod.GET,
                                                    entity,
                                                    new ParameterizedTypeReference<
                                                            Map<String, Object>>() {});

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> responseBody =
                                                (Map<String, Object>) response.getBody();

                                        if (responseBody.containsKey("error")) {
                                            throw new RuntimeException(
                                                    "Binom API error: "
                                                            + responseBody.get("error"));
                                        }

                                        // Parse info response
                                        CampaignInfoResponse.CampaignStats stats = null;
                                        String name = null;
                                        String status = "UNKNOWN";

                                        if (responseBody.containsKey("data")
                                                && responseBody.get("data") instanceof List) {
                                            List<Map<String, Object>> campaigns =
                                                    castToListOfMaps(responseBody.get("data"));
                                            // Find the specific campaign by ID
                                            for (Map<String, Object> camp : campaigns) {
                                                if (String.valueOf(camp.get("id"))
                                                        .equals(campaignId)) {
                                                    name = getString(camp, "name");
                                                    status =
                                                            getBooleanValue(
                                                                            camp,
                                                                            "is_deleted",
                                                                            false)
                                                                    ? "DELETED"
                                                                    : "ACTIVE";
                                                    stats =
                                                            CampaignInfoResponse.CampaignStats
                                                                    .builder()
                                                                    .clicks(
                                                                            getLongValue(
                                                                                    camp, "clicks",
                                                                                    0L))
                                                                    .conversions(
                                                                            getLongValue(
                                                                                    camp,
                                                                                    "leads_last_hour",
                                                                                    0L))
                                                                    .cost(
                                                                            getDoubleValue(
                                                                                    camp,
                                                                                    "profit_last_hour",
                                                                                    0.0))
                                                                    .revenue(0.0)
                                                                    .roi(0.0)
                                                                    .ctr(0.0)
                                                                    .cr(0.0)
                                                                    .build();
                                                    break;
                                                }
                                            }
                                        }

                                        if (stats == null) {
                                            // Default stats if not found
                                            stats =
                                                    CampaignInfoResponse.CampaignStats.builder()
                                                            .clicks(0L)
                                                            .conversions(0L)
                                                            .cost(0.0)
                                                            .revenue(0.0)
                                                            .roi(0.0)
                                                            .ctr(0.0)
                                                            .cr(0.0)
                                                            .build();
                                        }

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
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> responseBody =
                                                (Map<String, Object>) response.getBody();

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

            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            new ParameterizedTypeReference<Map<String, Object>>() {});

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
    private void validateResponse(
            ResponseEntity<Map<String, Object>> response, String endpoint, String operation) {
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
        Map<String, Object> responseBody = response.getBody();
        String errorMessage = buildErrorMessage(status, responseBody, endpoint, operation);

        switch (status) {
            case BAD_REQUEST: // 400
                log.error("Bad request to Binom API: {} - {}", endpoint, errorMessage);
                throw new BinomApiException(
                        "Bad request: " + errorMessage,
                        status,
                        extractBinomErrorCode(responseBody),
                        endpoint);

            case UNAUTHORIZED: // 401
                log.error("Unauthorized access to Binom API: {} - Check API key", endpoint);
                throw new BinomApiException(
                        "Unauthorized: Invalid or expired API key - " + errorMessage,
                        status,
                        extractBinomErrorCode(responseBody),
                        endpoint);

            case FORBIDDEN: // 403
                log.error("Forbidden access to Binom API: {} - Insufficient permissions", endpoint);
                throw new BinomApiException(
                        "Forbidden: Insufficient permissions for operation - " + errorMessage,
                        status,
                        extractBinomErrorCode(responseBody),
                        endpoint);

            case NOT_FOUND: // 404
                log.warn("Resource not found in Binom API: {} - {}", endpoint, errorMessage);
                throw new BinomApiException(
                        "Not found: " + errorMessage,
                        status,
                        extractBinomErrorCode(responseBody),
                        endpoint);

            case I_AM_A_TEAPOT: // 418
                log.warn(
                        "Binom API returned teapot status: {} - Rate limiting or maintenance",
                        endpoint);
                throw new BinomApiException(
                        "Service temporarily unavailable (teapot response): " + errorMessage,
                        status,
                        extractBinomErrorCode(responseBody),
                        endpoint);

            case TOO_MANY_REQUESTS: // 429
                log.warn("Rate limit exceeded for Binom API: {} - {}", endpoint, errorMessage);
                throw new BinomApiException(
                        "Rate limit exceeded: " + errorMessage,
                        status,
                        extractBinomErrorCode(responseBody),
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
                            extractBinomErrorCode(responseBody),
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
                            extractBinomErrorCode(responseBody),
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
                            extractBinomErrorCode(responseBody),
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

                                    ResponseEntity<Map<String, Object>> response =
                                            restTemplate.exchange(
                                                    url,
                                                    HttpMethod.GET,
                                                    entity,
                                                    new ParameterizedTypeReference<
                                                            Map<String, Object>>() {});

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> responseBody =
                                                (Map<String, Object>) response.getBody();

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
                                                            castToMap(offers.get(0));
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
     * Update campaign status (pause/resume) using Binom Click API Uses the Click API endpoint with
     * action parameter
     */
    public boolean updateCampaignStatus(String campaignId, String status) {
        try {
            // Using Click API for campaign management as per your Binom instance
            String url =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/click")
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
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) response.getBody();
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

    /** Get campaigns list using Binom Click API with campaigns action */
    public Map<String, Object> getCampaigns(List<String> campaignIds) {
        try {
            // Using Binom V2 API endpoint /public/api/v1/info/campaign
            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/public/api/v1/info/campaign")
                            .queryParam("datePreset", "all_time")
                            .queryParam("timezone", "UTC")
                            .queryParam("limit", 1000);

            if (campaignIds != null && !campaignIds.isEmpty()) {
                // Add specific campaign IDs if provided
                for (String id : campaignIds) {
                    builder.queryParam("id[]", id);
                }
            }

            String url = builder.build().toUriString();

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<String> entity = new HttpEntity<>("", headers);

            ResponseEntity<List<Map<String, Object>>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            new ParameterizedTypeReference<List<Map<String, Object>>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> result = new HashMap<>();
                List<Map<String, Object>> campaigns = response.getBody();

                // Filter out the totals row if present
                campaigns =
                        campaigns.stream()
                                .filter(c -> !"totals".equals(c.get("id")))
                                .collect(Collectors.toList());

                result.put("campaigns", campaigns);
                result.put("data", campaigns); // Also add as data for compatibility
                log.info("Fetched {} campaigns from Binom", campaigns.size());
                return result;
            }

            log.warn("No campaigns received from Binom API");
            return new HashMap<>();
        } catch (Exception e) {
            log.error("Failed to fetch campaigns: {}", e.getMessage(), e);
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("campaigns", new ArrayList<>());
            return emptyResult;
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
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/public/api/v1/stats/campaign")
                            .queryParam("datePreset", dateFrom != null ? "custom_time" : "all_time")
                            .queryParam("dateFrom", dateFrom != null ? dateFrom : "")
                            .queryParam("dateTo", dateTo != null ? dateTo : "")
                            .queryParam("timezone", "UTC")
                            .queryParam("sortColumn", "clicks")
                            .queryParam("sortType", "desc")
                            .build()
                            .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "SMM-Panel/2.0");
            headers.set(API_KEY_HEADER, apiKey);

            HttpEntity<String> entity = new HttpEntity<>("", headers);

            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                // Parse the response according to Binom v2 stats format
                if (body.containsKey("stats") && body.get("stats") instanceof List) {
                    List<Map<String, Object>> stats = castToListOfMaps(body.get("stats"));
                    // Find the campaign in stats list
                    for (Map<String, Object> stat : stats) {
                        if (stat.containsKey("id")
                                && String.valueOf(stat.get("id")).equals(campaignId)) {
                            return CampaignStatsResponse.builder()
                                    .campaignId(campaignId)
                                    .clicks(getLongValue(stat, "clicks", 0L))
                                    .conversions(getLongValue(stat, "leads", 0L))
                                    .cost(BigDecimal.valueOf(getDoubleValue(stat, "costs", 0.0)))
                                    .revenue(
                                            BigDecimal.valueOf(
                                                    getDoubleValue(stat, "revenue", 0.0)))
                                    .status("ACTIVE")
                                    .build();
                        }
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

    /**
     * Send conversion postback to Binom This notifies Binom that a click has converted (completed
     * desired action) Uses the standard Binom postback format
     */
    public boolean sendConversionPostback(String clickId, BigDecimal payout, String status) {
        try {
            // Binom postback URL format for conversions
            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/click")
                            .queryParam("cnv_id", clickId) // Click ID to convert
                            .queryParam("payout", payout != null ? payout.toString() : "0");

            // Add conversion status (lead, sale, reject, trash, etc.)
            if (status != null && !status.isEmpty()) {
                builder.queryParam("cnv_status", status);
            }

            // Add goal if specified (for multi-goal tracking)
            // builder.queryParam("goal", "1"); // Optional: goal number

            String url = builder.build().toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "SMM-Panel/2.0");

            HttpEntity<String> entity = new HttpEntity<>("", headers);

            log.info(
                    "Sending conversion postback for click {}: payout={}, status={}",
                    clickId,
                    payout,
                    status);

            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully sent conversion postback for click {}", clickId);
                return true;
            }

            log.warn("Postback returned non-success status: {}", response.getStatusCode());
            return false;
        } catch (Exception e) {
            log.error(
                    "Failed to send conversion postback for click {}: {}", clickId, e.getMessage());
            return false;
        }
    }

    /**
     * Get click statistics for a campaign Uses V2 API stats/campaign endpoint to fetch detailed
     * campaign data including clicks This is the correct V2 endpoint per official documentation
     */
    public Map<String, Object> getClickStats(String campaignId, String dateFrom, String dateTo) {
        try {
            // Use the correct V2 stats endpoint - stats/campaign not stats/clicks
            String url =
                    UriComponentsBuilder.fromHttpUrl(apiUrl + "/public/api/v1/stats/campaign")
                            .queryParam("campaignIds", campaignId) // Filter by campaign ID
                            .queryParam("datePreset", dateFrom != null ? "custom_time" : "all_time")
                            .queryParam("dateFrom", dateFrom != null ? dateFrom : "")
                            .queryParam("dateTo", dateTo != null ? dateTo : "")
                            .queryParam("timezone", "UTC")
                            .queryParam("limit", 100)
                            .build()
                            .toUriString();

            HttpHeaders headers = createAuthHeaders();
            HttpEntity<String> entity = new HttpEntity<>("", headers);

            ResponseEntity<Map<String, Object>> response =
                    restTemplate.exchange(
                            url,
                            HttpMethod.GET,
                            entity,
                            new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Retrieved campaign stats including clicks for campaign {}", campaignId);
                return response.getBody();
            }

            return new HashMap<>();
        } catch (Exception e) {
            log.error("Failed to get campaign stats for {}: {}", campaignId, e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Set campaign budget/click limits This is how you control the number of clicks in Binom Note:
     * Binom doesn't set limits on offers, only on campaigns
     */
    public boolean setCampaignLimits(
            String campaignId, Integer dailyCap, Integer totalCap, BigDecimal budgetLimit) {
        try {
            String endpoint = String.format("/public/api/v1/campaign/%s", campaignId);
            String url = apiUrl + endpoint;

            // First get the campaign to preserve existing settings
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<String> getEntity = new HttpEntity<>("", headers);

            ResponseEntity<Map> getResponse =
                    restTemplate.exchange(url, HttpMethod.GET, getEntity, Map.class);

            if (!getResponse.getStatusCode().is2xxSuccessful() || getResponse.getBody() == null) {
                log.error("Failed to get campaign {} for limit update", campaignId);
                return false;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> campaign = (Map<String, Object>) getResponse.getBody();

            // Preserve required fields that must be present in the update
            // These fields are mandatory for Binom API v2
            if (!campaign.containsKey("costModel")) {
                campaign.put("costModel", "CPC");
            }
            if (!campaign.containsKey("currency")) {
                campaign.put("currency", "USD");
            }
            if (!campaign.containsKey("hideReferrerType")) {
                campaign.put("hideReferrerType", "NONE");
            }

            // Update campaign settings with limits
            Map<String, Object> campaignSettings =
                    castToMap(campaign.getOrDefault("campaignSettings", new HashMap<>()));

            if (dailyCap != null) {
                campaignSettings.put("dailyCap", dailyCap);
                campaignSettings.put("dailyCapAction", "pause"); // Pause when daily cap reached
            }

            if (totalCap != null) {
                campaignSettings.put("totalCap", totalCap);
                campaignSettings.put("totalCapAction", "pause"); // Pause when total cap reached
            }

            if (budgetLimit != null) {
                campaignSettings.put("budgetLimit", budgetLimit.toString());
                campaignSettings.put("budgetLimitAction", "pause"); // Pause when budget reached
            }

            campaign.put("campaignSettings", campaignSettings);

            // Send update
            HttpEntity<Map<String, Object>> updateEntity = new HttpEntity<>(campaign, headers);

            ResponseEntity<Map> updateResponse =
                    restTemplate.exchange(url, HttpMethod.PUT, updateEntity, Map.class);

            if (updateResponse.getStatusCode().is2xxSuccessful()) {
                log.info(
                        "Successfully set limits for campaign {}: dailyCap={}, totalCap={},"
                                + " budget={}",
                        campaignId,
                        dailyCap,
                        totalCap,
                        budgetLimit);
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("Failed to set campaign limits: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Generate tracking URL for an offer through a campaign This is the proper way to track clicks
     * in Binom V2 Users should visit this URL, and Binom will track the click automatically
     */
    public String generateTrackingUrl(String campaignKey, Map<String, String> parameters) {
        if (campaignKey == null || campaignKey.isEmpty()) {
            throw new IllegalArgumentException("Campaign key is required for tracking URL");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(apiUrl + "/click");
        builder.queryParam("key", campaignKey);

        // Add any additional tracking parameters (sub1, sub2, etc.)
        if (parameters != null) {
            parameters.forEach(builder::queryParam);
        }

        String trackingUrl = builder.build().toUriString();
        log.info("Generated tracking URL for campaign {}: {}", campaignKey, trackingUrl);
        return trackingUrl;
    }

    // Helper methods for safe type casting
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castToListOfMaps(Object obj) {
        if (obj instanceof List<?>) {
            return (List<Map<String, Object>>) obj;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object obj) {
        if (obj instanceof Map<?, ?>) {
            return (Map<String, Object>) obj;
        }
        return new HashMap<>();
    }

    /**
     * Get offer click statistics from Binom using the proper Report API Uses
     * /public/api/v1/report/offer endpoint for accurate click tracking This method specifically
     * tracks clicks on a single offer
     *
     * @param offerId The Binom offer ID to get click statistics for
     * @return OfferClickStats containing click information
     */
    public OfferClickStats getOfferClickStatistics(String offerId) {
        String endpoint = "/public/api/v1/report/offer";

        // Build URL with required parameters for report API
        String url =
                UriComponentsBuilder.fromHttpUrl(apiUrl + endpoint)
                        .queryParam("ids[]", offerId) // Required: specific offer ID to track
                        .queryParam(
                                "groupings[]", "offer") // Required: group by offer for click data
                        .queryParam("datePreset", "all_time") // Required: all time for total clicks
                        .queryParam("timezone", "UTC") // Required: timezone
                        .queryParam("sortColumn", "clicks") // Required: sort by clicks
                        .queryParam("sortType", "desc") // Required: sort direction
                        .queryParam("limit", 100)
                        .queryParam("offset", 0)
                        .build()
                        .toUriString();

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = createAuthHeaders();
                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    log.info(
                                            "Fetching click statistics for offer ID: {} using"
                                                    + " Report API",
                                            offerId);

                                    ResponseEntity<Map<String, Object>> response =
                                            restTemplate.exchange(
                                                    url,
                                                    HttpMethod.GET,
                                                    entity,
                                                    new ParameterizedTypeReference<
                                                            Map<String, Object>>() {});

                                    if (response.getStatusCode() == HttpStatus.OK
                                            && response.getBody() != null) {
                                        Map<String, Object> reportResponse = response.getBody();

                                        // Report API returns data in 'report' field
                                        if (reportResponse.containsKey("report")) {
                                            @SuppressWarnings("unchecked")
                                            List<Map<String, Object>> reportData =
                                                    (List<Map<String, Object>>)
                                                            reportResponse.get("report");

                                            // Find the offer data (excluding totals row)
                                            Map<String, Object> offerData =
                                                    reportData.stream()
                                                            .filter(
                                                                    row ->
                                                                            !("totals"
                                                                                            .equals(
                                                                                                    row
                                                                                                            .get(
                                                                                                                    "id"))
                                                                                    || "totals"
                                                                                            .equals(
                                                                                                    row
                                                                                                            .get(
                                                                                                                    "entity_id"))))
                                                            .filter(
                                                                    row ->
                                                                            String.valueOf(
                                                                                                    row
                                                                                                            .get(
                                                                                                                    "entity_id"))
                                                                                            .equals(
                                                                                                    offerId)
                                                                                    || String
                                                                                            .valueOf(
                                                                                                    row
                                                                                                            .get(
                                                                                                                    "id"))
                                                                                            .equals(
                                                                                                    offerId))
                                                            .findFirst()
                                                            .orElse(null);

                                            if (offerData != null) {
                                                // Extract click statistics from report data
                                                Long clicks = getLongValue(offerData, "clicks", 0L);
                                                Long conversions =
                                                        getLongValue(offerData, "conversions", 0L);
                                                Long leads = getLongValue(offerData, "leads", 0L);
                                                Double revenue =
                                                        getDoubleValue(offerData, "revenue", 0.0);
                                                Double cost =
                                                        getDoubleValue(offerData, "cost", 0.0);
                                                Double profit =
                                                        getDoubleValue(offerData, "profit", 0.0);
                                                Double roi = getDoubleValue(offerData, "roi", 0.0);
                                                Double ctr = getDoubleValue(offerData, "ctr", 0.0);
                                                Double cr = getDoubleValue(offerData, "cr", 0.0);

                                                log.info(
                                                        "Offer {} has {} total clicks from Report"
                                                                + " API",
                                                        offerId,
                                                        clicks);

                                                return OfferClickStats.builder()
                                                        .offerId(offerId)
                                                        .offerName(getString(offerData, "name"))
                                                        .clicks(clicks)
                                                        .clicksLastHour(
                                                                0L) // Report API doesn't provide
                                                        // hourly data
                                                        .leadsLastHour(0L)
                                                        .profitLastHour(BigDecimal.ZERO)
                                                        .status("FOUND")
                                                        .message(
                                                                "Click data retrieved from Report"
                                                                        + " API")
                                                        .build();
                                            }
                                        }

                                        log.warn(
                                                "Offer {} not found in Binom Report API response",
                                                offerId);
                                        return OfferClickStats.builder()
                                                .offerId(offerId)
                                                .clicks(0L)
                                                .clicksLastHour(0L)
                                                .leadsLastHour(0L)
                                                .profitLastHour(BigDecimal.ZERO)
                                                .status("NOT_FOUND")
                                                .message("Offer not found in report data")
                                                .build();
                                    }

                                    throw new RuntimeException(
                                            "Invalid response from Binom API: "
                                                    + response.getBody());
                                }));
    }

    /**
     * Get offer click statistics by offer name First finds the offer by name, then gets its
     * statistics
     *
     * @param offerName The name of the offer
     * @return OfferClickStats containing click information
     */
    public OfferClickStats getOfferClickStatisticsByName(String offerName) {
        try {
            // First check if offer exists and get its ID
            CheckOfferResponse checkResponse = checkOfferExists(offerName);
            if (checkResponse.isExists() && checkResponse.getOfferId() != null) {
                return getOfferClickStatistics(checkResponse.getOfferId());
            }

            log.warn("Offer with name {} not found", offerName);
            return OfferClickStats.builder()
                    .offerName(offerName)
                    .clicks(0L)
                    .clicksLastHour(0L)
                    .leadsLastHour(0L)
                    .profitLastHour(BigDecimal.ZERO)
                    .status("NOT_FOUND")
                    .message("Offer not found by name: " + offerName)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get offer statistics by name: {}", e.getMessage());
            return OfferClickStats.builder()
                    .offerName(offerName)
                    .clicks(0L)
                    .clicksLastHour(0L)
                    .leadsLastHour(0L)
                    .profitLastHour(BigDecimal.ZERO)
                    .status("ERROR")
                    .message("Failed to get statistics: " + e.getMessage())
                    .build();
        }
    }

    // Response DTO for offer click statistics
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OfferClickStats {
        private String offerId;
        private String offerName;
        private Long clicks;
        private Long clicksLastHour;
        private Long leadsLastHour;
        private BigDecimal profitLastHour;
        private String firstClickTime;
        private Long secondsSinceLastLead;
        private Double meanTimeToConvert;
        private String affiliateNetwork;
        private String url;
        private String countryCode;
        private String group;
        private boolean isDeleted;
        private boolean isNoted;
        private String status;
        private String message;
    }

    /**
     * Remove offer from campaign paths (does NOT delete the offer from Binom) This properly updates
     * the campaign's customRotation to remove the offer Uses PATCH for efficient partial update
     * when possible Invalidates cache after update
     *
     * @param campaignId Campaign to remove offer from
     * @param offerId Offer to remove from campaign paths
     */
    @org.springframework.cache.annotation.CacheEvict(
            value = "binomCampaignDetails",
            key = "#campaignId")
    public void removeOfferFromCampaign(String campaignId, String offerId) {
        String endpoint = String.format("/public/api/v1/campaign/%s", campaignId);
        String url = apiUrl + endpoint;

        try {
            // Step 1: GET current campaign configuration
            HttpHeaders headers = createAuthHeaders();
            HttpEntity<String> getEntity = new HttpEntity<>("", headers);

            ResponseEntity<Map> getResponse =
                    restTemplate.exchange(url, HttpMethod.GET, getEntity, Map.class);

            if (!getResponse.getStatusCode().is2xxSuccessful() || getResponse.getBody() == null) {
                log.error("Failed to get campaign {} for offer removal", campaignId);
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> campaign = (Map<String, Object>) getResponse.getBody();

            // Step 2: Remove offer from customRotation paths
            @SuppressWarnings("unchecked")
            Map<String, Object> customRotation =
                    (Map<String, Object>) campaign.get("customRotation");

            if (customRotation == null) {
                log.warn("Campaign {} has no customRotation", campaignId);
                return;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> defaultPaths =
                    castToListOfMaps(customRotation.get("defaultPaths"));

            if (defaultPaths == null || defaultPaths.isEmpty()) {
                log.warn("Campaign {} has no paths", campaignId);
                return;
            }

            boolean offerRemoved = false;
            for (Map<String, Object> path : defaultPaths) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> offers =
                        castToListOfMaps(path.getOrDefault("offers", new ArrayList<>()));

                // Remove offer from this path
                int sizeBefore = offers.size();
                offers.removeIf(offer -> String.valueOf(offer.get("offerId")).equals(offerId));

                if (offers.size() < sizeBefore) {
                    offerRemoved = true;
                    path.put("offers", offers);
                    log.info(
                            "Removed offer {} from path {} in campaign {}",
                            offerId,
                            path.get("id"),
                            campaignId);
                }
            }

            if (!offerRemoved) {
                log.info("Offer {} not found in campaign {} paths", offerId, campaignId);
                return;
            }

            // Step 3: Build update payload with required fields
            Map<String, Object> updatePayload = new HashMap<>();
            updatePayload.put("id", campaign.get("id"));
            updatePayload.put("name", campaign.get("name"));

            // Extract cost information properly
            @SuppressWarnings("unchecked")
            Map<String, Object> cost =
                    (Map<String, Object>) campaign.getOrDefault("cost", new HashMap<>());
            @SuppressWarnings("unchecked")
            Map<String, Object> money =
                    (Map<String, Object>) cost.getOrDefault("money", new HashMap<>());

            // Required fields for Binom V2 API
            updatePayload.put("costModel", cost.getOrDefault("model", "CPC"));
            updatePayload.put("amount", money.getOrDefault("amount", 0));
            updatePayload.put("currency", money.getOrDefault("currency", "USD"));
            updatePayload.put("isAuto", cost.getOrDefault("isAuto", false));

            @SuppressWarnings("unchecked")
            Map<String, Object> hideReferrer =
                    (Map<String, Object>) campaign.getOrDefault("hideReferrer", new HashMap<>());
            updatePayload.put("hideReferrerType", hideReferrer.getOrDefault("type", "NONE"));

            // Add other required fields
            updatePayload.put("domainUuid", campaign.get("domainUuid"));
            updatePayload.put(
                    "distributionType", campaign.getOrDefault("distributionType", "NORMAL"));
            updatePayload.put("key", campaign.get("key"));
            updatePayload.put("groupUuid", campaign.get("groupUuid"));

            // Include campaignSettings (REQUIRED by API - must always be present)
            @SuppressWarnings("unchecked")
            Map<String, Object> campaignSettings =
                    (Map<String, Object>)
                            campaign.getOrDefault("campaignSettings", new HashMap<>());
            // Ensure all required fields are present with defaults
            campaignSettings.putIfAbsent("s2sPostback", "");
            campaignSettings.putIfAbsent("ea", null);
            campaignSettings.putIfAbsent("postbackPercent", 100);
            campaignSettings.putIfAbsent("payoutPercent", 100);
            campaignSettings.putIfAbsent("trafficLossPercent", 0);
            campaignSettings.putIfAbsent("appendToCampaignUrl", "");
            campaignSettings.putIfAbsent("appendToOfferUrl", "");
            campaignSettings.putIfAbsent("appendToLandingUrl", "");
            updatePayload.put("campaignSettings", campaignSettings);

            if (campaign.containsKey("trafficSourceId")) {
                updatePayload.put("trafficSourceId", campaign.get("trafficSourceId"));
            }

            // Update customRotation with modified paths
            Map<String, Object> updatedCustomRotation = new HashMap<>();
            updatedCustomRotation.put("defaultPaths", defaultPaths);
            updatedCustomRotation.put(
                    "rules", customRotation.getOrDefault("rules", new ArrayList<>()));
            updatePayload.put("customRotation", updatedCustomRotation);

            // Step 4: PUT updated configuration back to Binom
            HttpEntity<Map<String, Object>> updateEntity = new HttpEntity<>(updatePayload, headers);

            log.info("Removing offer {} from campaign {} paths", offerId, campaignId);

            ResponseEntity<Map> updateResponse =
                    restTemplate.exchange(url, HttpMethod.PUT, updateEntity, Map.class);

            if (updateResponse.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully removed offer {} from campaign {}", offerId, campaignId);
            } else {
                log.error(
                        "Failed to update campaign {} after removing offer {}: Status {}",
                        campaignId,
                        offerId,
                        updateResponse.getStatusCode());
            }

        } catch (Exception e) {
            log.error(
                    "Error removing offer {} from campaign {}: {}",
                    offerId,
                    campaignId,
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Batch remove offer from multiple campaigns (typically campaigns 1, 3, 4) This follows the
     * same pattern as assignOfferToCampaigns for consistency
     *
     * @param offerId The offer ID to remove
     * @param campaignIds List of campaign IDs to remove the offer from
     * @return RemoveOfferResponse with consolidated results
     */
    public RemoveOfferResponse removeOfferFromCampaigns(String offerId, List<String> campaignIds) {
        if (campaignIds == null || campaignIds.isEmpty()) {
            log.warn("No campaign IDs provided for offer removal");
            return RemoveOfferResponse.builder()
                    .offerId(offerId)
                    .status("NO_CAMPAIGNS")
                    .message("No campaigns provided for removal")
                    .success(false)
                    .build();
        }

        List<String> successfulRemovals = new ArrayList<>();
        List<String> failedRemovals = new ArrayList<>();

        for (String campaignId : campaignIds) {
            try {
                removeOfferFromCampaign(campaignId, offerId);
                successfulRemovals.add(campaignId);
                log.info("Successfully removed offer {} from campaign {}", offerId, campaignId);
            } catch (Exception e) {
                // It's normal if offer wasn't in this campaign
                failedRemovals.add(campaignId);
                log.debug(
                        "Could not remove offer {} from campaign {} (may not have been assigned):"
                                + " {}",
                        offerId,
                        campaignId,
                        e.getMessage());
            }
        }

        // Build consolidated response
        String status =
                failedRemovals.isEmpty()
                        ? "ALL_REMOVED"
                        : successfulRemovals.isEmpty() ? "NONE_REMOVED" : "PARTIAL_REMOVED";

        String message =
                String.format(
                        "Removed from %d/%d campaigns. Successful: %s, Failed/NotFound: %s",
                        successfulRemovals.size(),
                        campaignIds.size(),
                        successfulRemovals,
                        failedRemovals);

        return RemoveOfferResponse.builder()
                .offerId(offerId)
                .status(status)
                .message(message)
                .success(!successfulRemovals.isEmpty())
                .campaignIds(successfulRemovals)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemoveOfferResponse {
        private String offerId;
        private String status;
        private String message;
        private boolean success;
        private List<String> campaignIds;
    }

    // Keep old method name for backward compatibility but mark as deprecated
    @Deprecated
    public void unassignOfferFromCampaign(String campaignId, String offerId) {
        log.warn("unassignOfferFromCampaign is deprecated, use removeOfferFromCampaign instead");
        removeOfferFromCampaign(campaignId, offerId);
    }

    /**
     * Get detailed campaign information including offers Uses /public/api/v1/campaign/{id} endpoint
     * CACHED for 15 seconds to reduce API calls during sync cycles SYNCHRONIZED: Only one thread
     * per campaignId will call the API, others wait for cached result
     *
     * @param campaignId Campaign ID to get details for
     * @return Map containing campaign details including offers array
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "binomCampaignDetails",
            key = "#campaignId",
            unless = "#result == null || #result.isEmpty()",
            sync = true)
    public Map<String, Object> getCampaignDetails(String campaignId) {
        String endpoint = String.format("/public/api/v1/campaign/%s", campaignId);
        String url = apiUrl + endpoint;

        return circuitBreaker.executeSupplier(
                () ->
                        readRetry.executeSupplier(
                                () -> {
                                    HttpHeaders headers = createAuthHeaders();
                                    HttpEntity<String> entity = new HttpEntity<>("", headers);

                                    log.debug("Fetching details for campaign {}", campaignId);

                                    ResponseEntity<Map<String, Object>> response =
                                            restTemplate.exchange(
                                                    url,
                                                    HttpMethod.GET,
                                                    entity,
                                                    new ParameterizedTypeReference<
                                                            Map<String, Object>>() {});

                                    if (response.getStatusCode().is2xxSuccessful()
                                            && response.getBody() != null) {
                                        log.debug(
                                                "Successfully fetched campaign {} details",
                                                campaignId);
                                        return response.getBody();
                                    }

                                    log.warn("Failed to get campaign {} details", campaignId);
                                    return new HashMap<>();
                                }));
    }

    /**
     * Check if campaigns 1, 3, 4 exist in Binom
     *
     * @return true if all required campaigns exist
     */
    public boolean checkRequiredCampaignsExist() {
        List<String> requiredCampaigns = Arrays.asList("1", "3", "4");

        for (String campaignId : requiredCampaigns) {
            Map<String, Object> campaign = getCampaignDetails(campaignId);
            if (campaign.isEmpty() || campaign.get("id") == null) {
                log.error("Required campaign {} does not exist in Binom", campaignId);
                return false;
            }
        }

        log.info("All required campaigns (1, 3, 4) exist in Binom");
        return true;
    }

    /**
     * Check if a specific offer exists in a campaign
     *
     * @param campaignId Campaign ID to check
     * @param offerId Offer ID to look for
     * @return true if the offer exists in the campaign
     */
    public boolean campaignContainsOffer(String campaignId, String offerId) {
        try {
            Map<String, Object> campaign = getCampaignDetails(campaignId);
            if (campaign.isEmpty()) {
                return false;
            }

            // Check in customRotation -> defaultPaths -> offers
            Map<String, Object> customRotation =
                    (Map<String, Object>) campaign.get("customRotation");
            if (customRotation != null) {
                List<Map<String, Object>> defaultPaths =
                        (List<Map<String, Object>>) customRotation.get("defaultPaths");
                if (defaultPaths != null) {
                    for (Map<String, Object> path : defaultPaths) {
                        List<Map<String, Object>> offers =
                                (List<Map<String, Object>>) path.get("offers");
                        if (offers != null) {
                            for (Map<String, Object> offer : offers) {
                                String offerIdInPath = String.valueOf(offer.get("offerId"));
                                if (offerId.equals(offerIdInPath)) {
                                    log.debug(
                                            "Found offer {} in campaign {} path",
                                            offerId,
                                            campaignId);
                                    return true;
                                }
                            }
                        }
                    }
                }

                // Also check in rules -> paths -> offers
                List<Map<String, Object>> rules =
                        (List<Map<String, Object>>) customRotation.get("rules");
                if (rules != null) {
                    for (Map<String, Object> rule : rules) {
                        List<Map<String, Object>> paths =
                                (List<Map<String, Object>>) rule.get("paths");
                        if (paths != null) {
                            for (Map<String, Object> path : paths) {
                                List<Map<String, Object>> offers =
                                        (List<Map<String, Object>>) path.get("offers");
                                if (offers != null) {
                                    for (Map<String, Object> offer : offers) {
                                        String offerIdInPath = String.valueOf(offer.get("offerId"));
                                        if (offerId.equals(offerIdInPath)) {
                                            log.debug(
                                                    "Found offer {} in campaign {} rule",
                                                    offerId,
                                                    campaignId);
                                            return true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Error checking campaign {} for offer {}: {}",
                    campaignId,
                    offerId,
                    e.getMessage());
        }

        return false;
    }

    /**
     * Check which of campaigns 1, 3, 4 contain a specific offer
     *
     * @param offerId Offer ID to check
     * @return List of campaign IDs that contain the offer
     */
    public List<String> getCampaignsWithOffer(String offerId) {
        List<String> campaignsWithOffer = new ArrayList<>();
        List<String> campaignIds = Arrays.asList("1", "3", "4");

        for (String campaignId : campaignIds) {
            if (campaignContainsOffer(campaignId, offerId)) {
                campaignsWithOffer.add(campaignId);
            }
        }

        log.info(
                "Offer {} found in {} campaigns: {}",
                offerId,
                campaignsWithOffer.size(),
                campaignsWithOffer);
        return campaignsWithOffer;
    }
}

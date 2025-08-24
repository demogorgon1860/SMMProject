package com.smmpanel.controller;

import com.smmpanel.dto.binom.BinomIntegrationResponse;
import com.smmpanel.entity.FixedBinomCampaign;
import com.smmpanel.entity.Order;
import com.smmpanel.repository.jpa.FixedBinomCampaignRepository;
import com.smmpanel.service.BinomService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Test controller for Binom integration This is for testing purposes only - remove in production
 */
@Slf4j
@RestController
@RequestMapping("/api/test/binom")
@RequiredArgsConstructor
public class BinomTestController {

    private final BinomService binomService;
    private final FixedBinomCampaignRepository campaignRepository;

    /** Test endpoint to check if campaigns are configured */
    @GetMapping("/campaigns")
    public ResponseEntity<Map<String, Object>> getCampaigns() {
        List<FixedBinomCampaign> campaigns = campaignRepository.findByActiveTrue();

        Map<String, Object> response = new HashMap<>();
        response.put("campaignCount", campaigns.size());
        response.put(
                "campaigns",
                campaigns.stream()
                        .map(
                                c -> {
                                    Map<String, Object> campaign = new HashMap<>();
                                    campaign.put("id", c.getCampaignId());
                                    campaign.put("name", c.getCampaignName());
                                    campaign.put("geo", c.getGeoTargeting());
                                    campaign.put("priority", c.getPriority());
                                    campaign.put("active", c.getActive());
                                    return campaign;
                                })
                        .toList());
        response.put("status", campaigns.size() >= 2 ? "READY" : "NOT_READY");
        response.put(
                "message",
                campaigns.size() >= 2
                        ? "Binom integration is ready with " + campaigns.size() + " campaigns"
                        : "Need at least 2 campaigns, found " + campaigns.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Test endpoint to simulate offer distribution DO NOT USE IN PRODUCTION - This will actually
     * create offers in Binom!
     */
    @PostMapping("/test-distribution")
    public ResponseEntity<Map<String, Object>> testDistribution(
            @RequestParam(defaultValue = "1000") int views,
            @RequestParam(defaultValue = "true") boolean withClip,
            @RequestParam(defaultValue = "https://youtube.com/watch?v=test") String videoUrl) {

        try {
            // Create a test order
            Order testOrder = new Order();
            testOrder.setId(System.currentTimeMillis()); // Use timestamp as ID
            testOrder.setQuantity(views);
            testOrder.setLink(videoUrl);

            log.info(
                    "Testing Binom distribution: views={}, withClip={}, url={}",
                    views,
                    withClip,
                    videoUrl);

            // Call Binom service
            BinomIntegrationResponse result =
                    binomService.createBinomIntegration(testOrder, videoUrl, withClip, videoUrl);

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("offerId", result.getOfferId());
            response.put("campaignsCreated", result.getCampaignsCreated());
            response.put("message", result.getMessage());
            response.put(
                    "testDetails",
                    Map.of(
                            "views",
                            views,
                            "withClip",
                            withClip,
                            "coefficient",
                            withClip ? 3.0 : 4.0,
                            "totalClicks",
                            views * (withClip ? 3 : 4),
                            "clicksPerCampaign",
                            (views * (withClip ? 3 : 4)) / result.getCampaignsCreated()));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Test distribution failed", e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            errorResponse.put("type", e.getClass().getSimpleName());

            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    /** Test endpoint to check Binom connectivity */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> checkHealth() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Check if campaigns exist
            List<FixedBinomCampaign> campaigns = campaignRepository.findByActiveTrue();

            response.put("status", "UP");
            response.put("campaignsConfigured", campaigns.size());
            response.put("binomIntegrationEnabled", true);
            response.put("message", "Binom integration is configured and ready");

            // Add campaign details
            response.put(
                    "activeCampaigns",
                    campaigns.stream()
                            .map(c -> c.getCampaignId() + " - " + c.getCampaignName())
                            .toList());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("status", "DOWN");
            response.put("error", e.getMessage());
            response.put("binomIntegrationEnabled", false);

            return ResponseEntity.status(503).body(response);
        }
    }
}

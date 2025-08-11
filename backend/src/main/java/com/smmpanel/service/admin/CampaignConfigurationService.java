package com.smmpanel.service.admin;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.admin.CampaignConfigurationRequest;
import com.smmpanel.dto.admin.CampaignStatusResponse;
import com.smmpanel.dto.admin.ValidationResult;
import com.smmpanel.entity.FixedBinomCampaign;
import com.smmpanel.repository.jpa.BinomCampaignRepository;
import com.smmpanel.repository.jpa.FixedBinomCampaignRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignConfigurationService {

    private final FixedBinomCampaignRepository fixedBinomCampaignRepository;
    private final BinomCampaignRepository binomCampaignRepository;
    private final BinomClient binomClient;

    /** CRITICAL: Get all configured campaigns for admin review */
    public List<CampaignStatusResponse> getAllCampaignConfigurations() {
        List<FixedBinomCampaign> campaigns = fixedBinomCampaignRepository.findAll();
        return campaigns.stream().map(this::mapToCampaignStatus).collect(Collectors.toList());
    }

    /** CRITICAL: Add new campaign configuration */
    @Transactional
    public CampaignStatusResponse addCampaignConfiguration(CampaignConfigurationRequest request) {
        boolean campaignExists = verifyCampaignInBinom(request.getCampaignId());
        if (!campaignExists) {
            throw new IllegalArgumentException(
                    "Campaign "
                            + request.getCampaignId()
                            + " does not exist in Binom tracker. "
                            + "Please create it in Binom first, then add it here.");
        }
        if (fixedBinomCampaignRepository.existsByCampaignId(request.getCampaignId())) {
            throw new IllegalArgumentException(
                    "Campaign " + request.getCampaignId() + " is already configured");
        }
        FixedBinomCampaign campaign = new FixedBinomCampaign();
        campaign.setCampaignId(request.getCampaignId());
        campaign.setCampaignName(request.getCampaignName());
        campaign.setGeoTargeting(request.getGeoTargeting());
        campaign.setPriority(request.getPriority());
        campaign.setWeight(request.getWeight());
        campaign.setActive(true);
        campaign.setDescription(request.getDescription());
        campaign.setCreatedAt(LocalDateTime.now());
        campaign.setUpdatedAt(LocalDateTime.now());
        campaign = fixedBinomCampaignRepository.save(campaign);
        log.info(
                "Added campaign configuration: {} - {}",
                request.getCampaignId(),
                request.getCampaignName());
        return mapToCampaignStatus(campaign);
    }

    /** CRITICAL: Update campaign configuration */
    @Transactional
    public CampaignStatusResponse updateCampaignConfiguration(
            Long id, CampaignConfigurationRequest request) {
        FixedBinomCampaign campaign =
                fixedBinomCampaignRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Campaign configuration not found: " + id));
        campaign.setCampaignName(request.getCampaignName());
        campaign.setGeoTargeting(request.getGeoTargeting());
        campaign.setPriority(request.getPriority());
        campaign.setWeight(request.getWeight());
        campaign.setActive(request.getActive());
        campaign.setDescription(request.getDescription());
        campaign.setUpdatedAt(LocalDateTime.now());
        campaign = fixedBinomCampaignRepository.save(campaign);
        log.info("Updated campaign configuration: {}", campaign.getCampaignId());
        return mapToCampaignStatus(campaign);
    }

    /** CRITICAL: Remove campaign configuration */
    @Transactional
    public void removeCampaignConfiguration(Long id) {
        FixedBinomCampaign campaign =
                fixedBinomCampaignRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Campaign configuration not found: " + id));
        long activeAssignments = getActiveCampaignAssignments(campaign.getCampaignId());
        if (activeAssignments > 0) {
            throw new IllegalArgumentException(
                    "Cannot remove campaign "
                            + campaign.getCampaignId()
                            + " - it has "
                            + activeAssignments
                            + " active assignments");
        }
        fixedBinomCampaignRepository.delete(campaign);
        log.info("Removed campaign configuration: {}", campaign.getCampaignId());
    }

    /** CRITICAL: Test campaign connectivity with Binom */
    public boolean testCampaignConnectivity(String campaignId) {
        try {
            return verifyCampaignInBinom(campaignId);
        } catch (Exception e) {
            log.error(
                    "Failed to test campaign connectivity for {}: {}", campaignId, e.getMessage());
            return false;
        }
    }

    /** CRITICAL: Validate that exactly 3 campaigns are active */
    public ValidationResult validateCampaignConfiguration() {
        List<FixedBinomCampaign> activeCampaigns = fixedBinomCampaignRepository.findByActiveTrue();
        ValidationResult result = new ValidationResult();
        result.setValid(activeCampaigns.size() == 3);
        result.setActiveCampaignCount(activeCampaigns.size());
        if (activeCampaigns.size() == 3) {
            result.setMessage("✅ Perfect! Exactly 3 campaigns configured and active.");
            boolean allConnected = true;
            for (FixedBinomCampaign campaign : activeCampaigns) {
                if (!testCampaignConnectivity(campaign.getCampaignId())) {
                    allConnected = false;
                    result.getErrors()
                            .add(
                                    "Campaign "
                                            + campaign.getCampaignId()
                                            + " not reachable in Binom");
                }
            }
            if (allConnected) {
                result.setMessage("✅ Perfect! All 3 campaigns are active and connected to Binom.");
            } else {
                result.setValid(false);
                result.setMessage("⚠️ Campaigns configured but some are not reachable in Binom.");
            }
        } else if (activeCampaigns.size() < 3) {
            result.setMessage(
                    "❌ CRITICAL: Only "
                            + activeCampaigns.size()
                            + " campaigns active. Need exactly 3!");
            result.getErrors()
                    .add("Please configure " + (3 - activeCampaigns.size()) + " more campaigns");
        } else {
            result.setMessage(
                    "⚠️ Too many campaigns active: "
                            + activeCampaigns.size()
                            + ". Need exactly 3!");
            result.getErrors()
                    .add("Please deactivate " + (activeCampaigns.size() - 3) + " campaigns");
        }
        return result;
    }

    // Private helper methods
    private boolean verifyCampaignInBinom(String campaignId) {
        try {
            return binomClient.campaignExists(campaignId);
        } catch (Exception e) {
            log.error("Failed to verify campaign {} in Binom: {}", campaignId, e.getMessage());
            return false;
        }
    }

    private long getActiveCampaignAssignments(String campaignId) {
        return binomCampaignRepository.countByCampaignIdAndStatus(campaignId, "ACTIVE");
    }

    private CampaignStatusResponse mapToCampaignStatus(FixedBinomCampaign campaign) {
        return CampaignStatusResponse.builder()
                .id(campaign.getId())
                .campaignId(campaign.getCampaignId())
                .campaignName(campaign.getCampaignName())
                .geoTargeting(campaign.getGeoTargeting())
                .priority(campaign.getPriority())
                .weight(campaign.getWeight())
                .active(campaign.getActive())
                .description(campaign.getDescription())
                .connected(testCampaignConnectivity(campaign.getCampaignId()))
                .createdAt(campaign.getCreatedAt())
                .updatedAt(campaign.getUpdatedAt())
                .build();
    }
}

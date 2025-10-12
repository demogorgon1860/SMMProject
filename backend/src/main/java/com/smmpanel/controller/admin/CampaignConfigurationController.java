package com.smmpanel.controller.admin;

import com.smmpanel.dto.admin.CampaignConfigurationRequest;
import com.smmpanel.dto.admin.CampaignStatusResponse;
import com.smmpanel.dto.admin.CampaignValidationResult;
import com.smmpanel.service.integration.BinomService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin controller for managing fixed Binom campaign configurations Now uses BinomService directly
 * for all Binom-related operations
 */
@RestController
@RequestMapping("/admin/campaigns")
@RequiredArgsConstructor
public class CampaignConfigurationController {

    private final BinomService binomService;

    @GetMapping
    public List<CampaignStatusResponse> getAllCampaigns() {
        return binomService.getAllCampaignConfigurations();
    }

    @PostMapping
    @SuppressWarnings("deprecation")
    public CampaignStatusResponse addCampaign(@RequestBody CampaignConfigurationRequest request) {
        return binomService.addCampaignConfiguration(request);
    }

    @PutMapping("/{id}")
    @SuppressWarnings("deprecation")
    public CampaignStatusResponse updateCampaign(
            @PathVariable Long id, @RequestBody CampaignConfigurationRequest request) {
        return binomService.updateCampaignConfiguration(id, request);
    }

    @DeleteMapping("/{id}")
    @SuppressWarnings("deprecation")
    public ResponseEntity<?> removeCampaign(@PathVariable Long id) {
        binomService.removeCampaignConfiguration(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/validate")
    public CampaignValidationResult validateCampaigns() {
        return binomService.validateCampaignConfiguration();
    }
}

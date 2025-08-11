package com.smmpanel.controller.admin;

import com.smmpanel.dto.admin.CampaignConfigurationRequest;
import com.smmpanel.dto.admin.CampaignStatusResponse;
import com.smmpanel.dto.admin.ValidationResult;
import com.smmpanel.service.admin.CampaignConfigurationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/campaigns")
@RequiredArgsConstructor
public class CampaignConfigurationController {

    private final CampaignConfigurationService campaignConfigurationService;

    @GetMapping
    public List<CampaignStatusResponse> getAllCampaigns() {
        return campaignConfigurationService.getAllCampaignConfigurations();
    }

    @PostMapping
    public CampaignStatusResponse addCampaign(@RequestBody CampaignConfigurationRequest request) {
        return campaignConfigurationService.addCampaignConfiguration(request);
    }

    @PutMapping("/{id}")
    public CampaignStatusResponse updateCampaign(
            @PathVariable Long id, @RequestBody CampaignConfigurationRequest request) {
        return campaignConfigurationService.updateCampaignConfiguration(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> removeCampaign(@PathVariable Long id) {
        campaignConfigurationService.removeCampaignConfiguration(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/validate")
    public ValidationResult validateCampaigns() {
        return campaignConfigurationService.validateCampaignConfiguration();
    }
}

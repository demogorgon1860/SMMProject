package com.smmpanel.service;

import com.smmpanel.client.BinomClient;
import com.smmpanel.dto.binom.CampaignResponse;
import com.smmpanel.dto.binom.CampaignStats;
import com.smmpanel.dto.binom.CreateCampaignRequest;
import com.smmpanel.entity.BinomCampaign;
import com.smmpanel.entity.ConversionCoefficient;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.TrafficSource;
import com.smmpanel.exception.BinomApiException;
import com.smmpanel.exception.NoAvailableTrafficSourceException;
import com.smmpanel.repository.BinomCampaignRepository;
import com.smmpanel.repository.ConversionCoefficientRepository;
import com.smmpanel.repository.TrafficSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class BinomService {

    private final BinomClient binomClient;
    private final BinomCampaignRepository campaignRepository;
    private final TrafficSourceRepository trafficSourceRepository;
    private final ConversionCoefficientRepository coefficientRepository;
    private final Random random = new Random();

    @Transactional
    public BinomCampaign createCampaign(Order order, String targetUrl, boolean hasClip) {
        try {
            // Get conversion coefficient
            BigDecimal coefficient = getConversionCoefficient(order.getService().getId(), hasClip);
            
            // Calculate required clicks
            int requiredClicks = (int) (order.getQuantity() * coefficient.doubleValue());
            
            // Select traffic source
            TrafficSource trafficSource = selectTrafficSource();
            
            // Create campaign in Binom
            CreateCampaignRequest request = CreateCampaignRequest.builder()
                    .name("SMM_Order_" + order.getId())
                    .url(targetUrl)
                    .trafficSourceId(trafficSource.getSourceId())
                    .countryCode("US") // Default, can be made configurable
                    .clicksLimit(requiredClicks)
                    .dailyLimit(Math.min(requiredClicks, 1000)) // Max 1000 clicks per day
                    .build();

            CampaignResponse response = binomClient.createCampaign(request);

            // Save campaign record
            BinomCampaign campaign = new BinomCampaign();
            campaign.setOrder(order);
            campaign.setCampaignId(response.getCampaignId());
            campaign.setOfferId(response.getOfferId());
            campaign.setTargetUrl(targetUrl);
            campaign.setTrafficSource(trafficSource);
            campaign.setCoefficient(coefficient);
            campaign.setClicksRequired(requiredClicks);
            campaign.setClicksDelivered(0);
            campaign.setViewsGenerated(0);
            campaign.setStatus("ACTIVE");

            campaign = campaignRepository.save(campaign);

            log.info("Created Binom campaign {} for order {} with {} required clicks", 
                    response.getCampaignId(), order.getId(), requiredClicks);

            return campaign;

        } catch (Exception e) {
            log.error("Failed to create Binom campaign for order {}: {}", order.getId(), e.getMessage(), e);
            throw new BinomApiException("Failed to create campaign: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void updateCampaignStats(String campaignId) {
        try {
            BinomCampaign campaign = campaignRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

            CampaignStats stats = binomClient.getCampaignStats(campaignId);

            campaign.setClicksDelivered(stats.getClicks());
            campaign.setViewsGenerated(stats.getConversions());

            // Update traffic source usage
            TrafficSource source = campaign.getTrafficSource();
            if (source.getLastResetDate().isBefore(LocalDate.now())) {
                source.setClicksUsedToday(0);
                source.setLastResetDate(LocalDate.now());
            }
            source.setClicksUsedToday(source.getClicksUsedToday() + stats.getClicks());
            trafficSourceRepository.save(source);

            campaignRepository.save(campaign);

            log.debug("Updated campaign {} stats: {} clicks, {} conversions", 
                    campaignId, stats.getClicks(), stats.getConversions());

        } catch (Exception e) {
            log.error("Failed to update campaign stats for {}: {}", campaignId, e.getMessage(), e);
        }
    }

    @Transactional
    public void stopCampaign(String campaignId) {
        try {
            BinomCampaign campaign = campaignRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

            binomClient.stopCampaign(campaignId);
            
            campaign.setStatus("STOPPED");
            campaignRepository.save(campaign);

            log.info("Stopped Binom campaign {}", campaignId);

        } catch (Exception e) {
            log.error("Failed to stop campaign {}: {}", campaignId, e.getMessage(), e);
            throw new BinomApiException("Failed to stop campaign: " + e.getMessage(), e);
        }
    }

    @Transactional
    public void resumeCampaign(String campaignId) {
        try {
            BinomCampaign campaign = campaignRepository.findByCampaignId(campaignId)
                    .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));

            binomClient.resumeCampaign(campaignId);
            
            campaign.setStatus("ACTIVE");
            campaignRepository.save(campaign);

            log.info("Resumed Binom campaign {}", campaignId);

        } catch (Exception e) {
            log.error("Failed to resume campaign {}: {}", campaignId, e.getMessage(), e);
            throw new BinomApiException("Failed to resume campaign: " + e.getMessage(), e);
        }
    }

    @Transactional
    public BinomCampaign createRefillCampaign(Order order, String targetUrl, int remainingViews, boolean hasClip) {
        try {
            // Get conversion coefficient
            BigDecimal coefficient = getConversionCoefficient(order.getService().getId(), hasClip);
            
            // Calculate required clicks for remaining views
            int requiredClicks = (int) (remainingViews * coefficient.doubleValue());
            
            // Select traffic source
            TrafficSource trafficSource = selectTrafficSource();
            
            // Create refill campaign in Binom
            CreateCampaignRequest request = CreateCampaignRequest.builder()
                    .name("SMM_Refill_" + order.getId() + "_" + System.currentTimeMillis())
                    .url(targetUrl)
                    .trafficSourceId(trafficSource.getSourceId())
                    .countryCode("US")
                    .clicksLimit(requiredClicks)
                    .dailyLimit(Math.min(requiredClicks, 500)) // Slower refill rate
                    .build();

            CampaignResponse response = binomClient.createCampaign(request);

            // Save refill campaign record
            BinomCampaign campaign = new BinomCampaign();
            campaign.setOrder(order);
            campaign.setCampaignId(response.getCampaignId());
            campaign.setOfferId(response.getOfferId());
            campaign.setTargetUrl(targetUrl);
            campaign.setTrafficSource(trafficSource);
            campaign.setCoefficient(coefficient);
            campaign.setClicksRequired(requiredClicks);
            campaign.setClicksDelivered(0);
            campaign.setViewsGenerated(0);
            campaign.setStatus("ACTIVE");

            campaign = campaignRepository.save(campaign);

            log.info("Created Binom refill campaign {} for order {} with {} required clicks", 
                    response.getCampaignId(), order.getId(), requiredClicks);

            return campaign;

        } catch (Exception e) {
            log.error("Failed to create Binom refill campaign for order {}: {}", order.getId(), e.getMessage(), e);
            throw new BinomApiException("Failed to create refill campaign: " + e.getMessage(), e);
        }
    }

    private BigDecimal getConversionCoefficient(Long serviceId, boolean hasClip) {
        Optional<ConversionCoefficient> coefficient = coefficientRepository.findByServiceId(serviceId);
        
        if (coefficient.isPresent()) {
            return hasClip ? coefficient.get().getWithClip() : coefficient.get().getWithoutClip();
        }
        
        // Default coefficients if not found
        return hasClip ? new BigDecimal("3.0") : new BigDecimal("4.0");
    }

    private TrafficSource selectTrafficSource() {
        List<TrafficSource> activeSources = trafficSourceRepository.findByActiveTrue();
        
        if (activeSources.isEmpty()) {
            throw new NoAvailableTrafficSourceException("No active traffic sources available");
        }

        // Filter sources that haven't exceeded daily limits
        LocalDate today = LocalDate.now();
        List<TrafficSource> availableSources = activeSources.stream()
                .filter(source -> {
                    // Reset daily counters if needed
                    if (source.getLastResetDate().isBefore(today)) {
                        source.setClicksUsedToday(0);
                        source.setLastResetDate(today);
                        trafficSourceRepository.save(source);
                    }
                    
                    // Check if source has available capacity
                    return source.getDailyLimit() == null || 
                           source.getClicksUsedToday() < source.getDailyLimit();
                })
                .toList();

        if (availableSources.isEmpty()) {
            throw new NoAvailableTrafficSourceException("All traffic sources have reached daily limits");
        }

        // Weighted random selection
        int totalWeight = availableSources.stream()
                .mapToInt(TrafficSource::getWeight)
                .sum();

        int randomWeight = random.nextInt(totalWeight);
        int currentWeight = 0;

        for (TrafficSource source : availableSources) {
            currentWeight += source.getWeight();
            if (randomWeight < currentWeight) {
                return source;
            }
        }

        // Fallback to first available source
        return availableSources.get(0);
    }

    public List<BinomCampaign> getActiveCampaignsForOrder(Long orderId) {
        return campaignRepository.findByOrderIdAndStatus(orderId, "ACTIVE");
    }

    public void updateCampaignStatus(String campaignId, String status) {
        BinomCampaign campaign = campaignRepository.findByCampaignId(campaignId)
                .orElseThrow(() -> new IllegalArgumentException("Campaign not found: " + campaignId));
        
        campaign.setStatus(status);
        campaignRepository.save(campaign);
    }
}
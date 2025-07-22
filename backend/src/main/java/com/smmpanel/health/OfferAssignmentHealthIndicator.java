package com.smmpanel.health;

import com.smmpanel.repository.FixedBinomCampaignRepository;
import com.smmpanel.client.BinomClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("offerAssignmentHealth")
@RequiredArgsConstructor
public class OfferAssignmentHealthIndicator implements HealthIndicator {

    private final FixedBinomCampaignRepository fixedCampaignRepository;
    private final BinomClient binomClient;

    @Override
    public Health health() {
        try {
            // Check if exactly 3 fixed campaigns are configured
            long activeCampaignsCount = fixedCampaignRepository.countActiveCampaigns();
            
            if (activeCampaignsCount != 3) {
                return Health.down()
                        .withDetail("active_campaigns", activeCampaignsCount)
                        .withDetail("expected_campaigns", 3)
                        .withDetail("error", "Incorrect number of active fixed campaigns")
                        .build();
            }

            // Test Binom API connectivity (simple check)
            try {
                binomClient.checkOfferExists("HEALTH_CHECK_OFFER");
                
                return Health.up()
                        .withDetail("active_campaigns", activeCampaignsCount)
                        .withDetail("binom_api", "accessible")
                        .build();
                        
            } catch (Exception binomError) {
                return Health.down()
                        .withDetail("active_campaigns", activeCampaignsCount)
                        .withDetail("binom_api", "error")
                        .withDetail("binom_error", binomError.getMessage())
                        .build();
            }

        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}

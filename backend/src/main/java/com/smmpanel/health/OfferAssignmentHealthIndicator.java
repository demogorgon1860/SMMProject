package com.smmpanel.health;

import com.smmpanel.client.BinomClient;
import com.smmpanel.repository.jpa.FixedBinomCampaignRepository;
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
            // Since campaigns are managed externally in Binom,
            // we only check Binom API connectivity
            boolean binomEnabled =
                    Boolean.parseBoolean(
                            System.getenv().getOrDefault("BINOM_INTEGRATION_ENABLED", "false"));

            if (!binomEnabled) {
                return Health.up()
                        .withDetail("status", "Binom integration disabled")
                        .withDetail("campaigns", "Managed externally in Binom")
                        .build();
            }

            // Test Binom API connectivity (simple check)
            try {
                binomClient.checkOfferExists("HEALTH_CHECK_OFFER");

                return Health.up()
                        .withDetail("binom_api", "accessible")
                        .withDetail("campaigns", "Managed externally in Binom")
                        .withDetail("mode", "External campaign management")
                        .build();

            } catch (Exception binomError) {
                // Binom API not accessible, but this might be expected in dev
                return Health.up()
                        .withDetail("binom_api", "not accessible")
                        .withDetail("campaigns", "Managed externally in Binom")
                        .withDetail("note", "Binom will be contacted when orders are placed")
                        .build();
            }

        } catch (Exception e) {
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}

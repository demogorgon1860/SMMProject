package com.smmpanel.producer;

import com.smmpanel.dto.binom.OfferAssignmentRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Producer for offer assignment events */
@Slf4j
@Component("binomOfferEventProducer")
@RequiredArgsConstructor
public class OfferEventProducer {

    private final KafkaProducers kafkaProducers;

    /** Send offer assignment event */
    public void sendOfferAssignmentEvent(
            Long orderId, String offerName, String targetUrl, String source) {
        try {
            OfferAssignmentRequest request =
                    OfferAssignmentRequest.builder()
                            .orderId(orderId)
                            .offerName(offerName)
                            .targetUrl(targetUrl)
                            .source(source)
                            .geoTargeting("US")
                            .useFixedCampaign(false)
                            .build();

            kafkaProducers.sendOfferAssignmentRequest(request);

            log.info(
                    "Sent offer assignment event for order: {} with offer: {}", orderId, offerName);

        } catch (Exception e) {
            log.error(
                    "Failed to send offer assignment event for order {}: {}",
                    orderId,
                    e.getMessage(),
                    e);
        }
    }
}

package com.smmpanel.service;

import com.smmpanel.event.OfferAssignmentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service("kafkaOfferEventProducer")
@RequiredArgsConstructor
public class OfferEventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private static final String TOPIC = "offer-assignment-events";

    /**
     * Отправка события для назначения оффера
     */
    public void sendOfferAssignmentEvent(Long orderId, String offerName, String targetUrl, String source) {
        try {
            OfferAssignmentEvent event = OfferAssignmentEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderId(orderId)
                    .offerName(offerName)
                    .targetUrl(targetUrl)
                    .geoTargeting("US")
                    .timestamp(LocalDateTime.now())
                    .source(source)
                    .build();

            kafkaTemplate.send(TOPIC, orderId.toString(), event);
            log.info("Sent offer assignment event for order {}: {}", orderId, offerName);

        } catch (Exception e) {
            log.error("Failed to send offer assignment event for order {}: {}", orderId, e.getMessage(), e);
        }
    }
}

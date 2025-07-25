package com.smmpanel.consumer;

import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.dto.binom.OfferAssignmentResponse;
import com.smmpanel.event.OfferAssignmentEvent;
import com.smmpanel.service.OfferAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

/**
 * Consumer для обработки событий назначения офферов
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferAssignmentConsumer {

    private final OfferAssignmentService offerAssignmentService;

    /**
     * Обработка событий назначения офферов на фиксированные кампании
     */
    @KafkaListener(
            topics = "offer-assignment-events",
            groupId = "offer-assignment-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleOfferAssignmentEvent(
            @Payload OfferAssignmentEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition
    ) {
        try {
            log.info("Processing offer assignment event for order {}: {}", event.getOrderId(), event.getOfferName());

            // Создаем запрос для назначения оффера
            OfferAssignmentRequest request = OfferAssignmentRequest.builder()
                    .offerName(event.getOfferName())
                    .targetUrl(event.getTargetUrl())
                    .orderId(event.getOrderId())
                    .description(event.getDescription())
                    .geoTargeting(event.getGeoTargeting())
                    .build();

            // Выполняем назначение на все 3 фиксированные кампании
            OfferAssignmentResponse response = offerAssignmentService.assignOfferToFixedCampaigns(request);

            if ("SUCCESS".equals(response.getStatus())) {
                log.info("Successfully processed offer assignment for order {}: {} campaigns created", 
                        event.getOrderId(), response.getCampaignsCreated());
            } else {
                log.error("Failed to process offer assignment for order {}: {}", 
                        event.getOrderId(), response.getMessage());
            }

        } catch (Exception e) {
            log.error("Error processing offer assignment event for order {}: {}", 
                    event.getOrderId(), e.getMessage(), e);
            // В production здесь можно добавить retry logic или DLQ
        }
    }
}

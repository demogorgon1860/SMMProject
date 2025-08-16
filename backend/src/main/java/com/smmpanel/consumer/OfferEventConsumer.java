package com.smmpanel.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.service.OfferAssignmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/** Consumer for offer assignment events */
@Slf4j
@Component
// Removed Lombok constructor to use explicit constructor with @Qualifier
public class OfferEventConsumer {

    private final OfferAssignmentService offerAssignmentService;
    private final ObjectMapper objectMapper;

    public OfferEventConsumer(
            OfferAssignmentService offerAssignmentService,
            @org.springframework.beans.factory.annotation.Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.offerAssignmentService = offerAssignmentService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "smm.offer.assignments",
            groupId = "offer-assignment-group",
            containerFactory = "kafkaListenerContainerFactory")
    @Retryable(
            value = {Exception.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 3000))
    public void handleOfferAssignment(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        try {
            log.info(
                    "Processing offer assignment from topic: {}, partition: {}, offset: {}",
                    topic,
                    partition,
                    offset);

            // Parse the offer assignment request
            OfferAssignmentRequest request =
                    objectMapper.readValue(message, OfferAssignmentRequest.class);

            // Process the assignment
            offerAssignmentService.assignOfferToFixedCampaigns(request);

            // Acknowledge successful processing
            acknowledgment.acknowledge();

            log.info("Successfully processed offer assignment for order: {}", request.getOrderId());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse offer assignment JSON: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse offer assignment", e);
        } catch (Exception e) {
            log.error("Failed to process offer assignment: {}", e.getMessage(), e);
            throw e;
        }
    }
}

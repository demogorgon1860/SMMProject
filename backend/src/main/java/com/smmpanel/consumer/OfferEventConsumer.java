package com.smmpanel.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.service.OfferAssignmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Consumer for offer assignment events
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfferEventConsumer {

    private final OfferAssignmentService offerAssignmentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "offer-assignments",
        groupId = "offer-assignment-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Retryable(value = {Exception.class}, maxAttempts = 3, backoff = @Backoff(delay = 3000))
    public void handleOfferAssignment(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        try {
            log.info("Processing offer assignment from topic: {}, partition: {}, offset: {}", 
                    topic, partition, offset);

            // Parse the offer assignment request
            OfferAssignmentRequest request = objectMapper.readValue(message, OfferAssignmentRequest.class);

            // Process the assignment
            offerAssignmentService.assignOffer(request);

            // Acknowledge successful processing
            acknowledgment.acknowledge();
            
            log.info("Successfully processed offer assignment for order: {}", request.getOrderId());

        } catch (Exception e) {
            log.error("Failed to process offer assignment: {}", e.getMessage(), e);
            throw e;
        }
    }
}

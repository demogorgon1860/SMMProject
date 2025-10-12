package com.smmpanel.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.dto.binom.OfferAssignmentRequest;
import com.smmpanel.service.integration.BinomService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/** Consumer for offer assignment events */
@Slf4j
@Component
// Removed Lombok constructor to use explicit constructor with @Qualifier
public class OfferEventConsumer {

    private final BinomService binomService;
    private final ObjectMapper objectMapper;

    public OfferEventConsumer(
            BinomService binomService,
            @org.springframework.beans.factory.annotation.Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.binomService = binomService;
        this.objectMapper = objectMapper;
    }

    @RetryableTopic(
            attempts = "3",
            backoff = @Backoff(delay = 2000, multiplier = 2.0),
            autoCreateTopics = "true",
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.FAIL_ON_ERROR,
            include = {Exception.class})
    @KafkaListener(
            topics = "smm.offer.assignments",
            groupId = "offer-assignment-group",
            containerFactory = "kafkaListenerContainerFactory")
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
            binomService.assignOfferToFixedCampaigns(request);

            // Acknowledge successful processing
            acknowledgment.acknowledge();

            log.info("Successfully processed offer assignment for order: {}", request.getOrderId());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse offer assignment JSON: {}", e.getMessage(), e);
            // Don't retry JSON parsing errors - send straight to DLT
            acknowledgment.acknowledge();
            throw new RuntimeException("Failed to parse offer assignment", e);
        } catch (Exception e) {
            log.error("Failed to process offer assignment: {}", e.getMessage(), e);
            // Let RetryableTopic handle retries via retry topics
            throw e;
        }
    }
}

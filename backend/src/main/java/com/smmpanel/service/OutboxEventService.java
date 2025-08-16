package com.smmpanel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smmpanel.entity.OutboxEvent;
import com.smmpanel.repository.jpa.OutboxEventRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transactional Outbox Service Handles reliable message publishing using the Outbox pattern */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventService {

    private static final int MAX_RETRIES = 5;
    private static final int BATCH_SIZE = 100;
    private static final int CLEANUP_DAYS = 7;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /** Store event in outbox within the same transaction */
    @Transactional
    public void publishEvent(
            String aggregateType,
            String aggregateId,
            String eventType,
            String topic,
            String partitionKey,
            Object payload,
            Map<String, String> headers) {

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headersJson = headers != null ? objectMapper.writeValueAsString(headers) : null;

            OutboxEvent event =
                    OutboxEvent.builder()
                            .aggregateType(aggregateType)
                            .aggregateId(aggregateId)
                            .eventType(eventType)
                            .topic(topic)
                            .partitionKey(partitionKey)
                            .payload(payloadJson)
                            .headers(headersJson)
                            .build();

            outboxEventRepository.save(event);

            log.debug(
                    "Outbox event stored: aggregateType={}, aggregateId={}, eventType={}, topic={}",
                    aggregateType,
                    aggregateId,
                    eventType,
                    topic);

        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to serialize outbox event payload: aggregateType={}, aggregateId={}",
                    aggregateType,
                    aggregateId,
                    e);
            throw new RuntimeException("Failed to store outbox event", e);
        }
    }

    /** Process unprocessed outbox events (scheduled) */
    @Scheduled(fixedDelay = 5000) // Every 5 seconds
    @Async("taskExecutor")
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> unprocessedEvents =
                outboxEventRepository.findUnprocessedEvents(
                        LocalDateTime.now(), MAX_RETRIES, PageRequest.of(0, BATCH_SIZE));

        if (!unprocessedEvents.isEmpty()) {
            log.debug("Processing {} unprocessed outbox events", unprocessedEvents.size());

            for (OutboxEvent event : unprocessedEvents) {
                processEvent(event);
            }
        }
    }

    /** Process individual outbox event */
    private void processEvent(OutboxEvent event) {
        try {
            // Deserialize payload
            Object payload = objectMapper.readValue(event.getPayload(), Object.class);

            // Deserialize headers if present
            Map<String, String> headers = null;
            if (event.getHeaders() != null) {
                headers = objectMapper.readValue(event.getHeaders(), Map.class);
            }

            // Send to Kafka
            kafkaTemplate
                    .send(event.getTopic(), event.getPartitionKey(), payload)
                    .whenComplete((result, ex) -> handleSendResult(event, result, ex));

        } catch (Exception e) {
            handleEventProcessingError(event, e);
        }
    }

    /** Handle Kafka send result */
    @Transactional
    public void handleSendResult(
            OutboxEvent event, SendResult<String, Object> result, Throwable ex) {
        if (ex == null) {
            // Success
            event.markProcessed();
            outboxEventRepository.save(event);

            log.debug(
                    "Outbox event published successfully: id={}, topic={}, partition={}, offset={}",
                    event.getId(),
                    event.getTopic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
        } else {
            // Failure
            handleEventProcessingError(event, ex);
        }
    }

    /** Handle event processing error */
    @Transactional
    public void handleEventProcessingError(OutboxEvent event, Throwable error) {
        event.markFailed(error.getMessage());
        outboxEventRepository.save(event);

        if (event.getRetryCount() >= MAX_RETRIES) {
            log.error(
                    "Outbox event failed after {} retries: id={}, aggregateType={}, eventType={},"
                            + " error={}",
                    MAX_RETRIES,
                    event.getId(),
                    event.getAggregateType(),
                    event.getEventType(),
                    error.getMessage());
        } else {
            log.warn(
                    "Outbox event failed, will retry: id={}, retryCount={}, nextRetryAt={},"
                            + " error={}",
                    event.getId(),
                    event.getRetryCount(),
                    event.getNextRetryAt(),
                    error.getMessage());
        }
    }

    /** Cleanup processed events (scheduled) */
    @Scheduled(cron = "0 0 2 * * ?") // Daily at 2 AM
    @Transactional
    public void cleanupProcessedEvents() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(CLEANUP_DAYS);
        int deletedCount = outboxEventRepository.deleteProcessedEventsOlderThan(cutoffTime);

        if (deletedCount > 0) {
            log.info(
                    "Cleaned up {} processed outbox events older than {} days",
                    deletedCount,
                    CLEANUP_DAYS);
        }
    }

    /** Get outbox statistics */
    public OutboxStats getOutboxStats() {
        long unprocessedCount = outboxEventRepository.countByProcessedFalse();
        long failedCount = outboxEventRepository.countFailedEvents(MAX_RETRIES);

        return new OutboxStats(unprocessedCount, failedCount);
    }

    /** Outbox statistics record */
    public record OutboxStats(long unprocessedCount, long failedCount) {}
}

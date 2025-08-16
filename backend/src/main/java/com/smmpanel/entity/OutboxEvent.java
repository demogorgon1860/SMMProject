package com.smmpanel.entity;

import com.fasterxml.jackson.annotation.JsonRawValue;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Transactional Outbox Event Entity Ensures reliable message publishing by storing events in
 * database transaction
 */
@Entity
@Table(
        name = "outbox_events",
        indexes = {
            @Index(name = "idx_outbox_processed_created", columnList = "processed, createdAt"),
            @Index(name = "idx_outbox_topic", columnList = "topic"),
            @Index(name = "idx_outbox_aggregate", columnList = "aggregateType, aggregateId")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 100)
    private String topic;

    @Column(name = "partition_key", length = 100)
    private String partitionKey;

    @Lob
    @JsonRawValue
    @Column(name = "payload", nullable = false)
    private String payload;

    @Lob
    @Column(name = "headers")
    private String headers;

    @Builder.Default
    @Column(name = "processed", nullable = false)
    private Boolean processed = false;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    public void markProcessed() {
        this.processed = true;
        this.processedAt = LocalDateTime.now();
    }

    public void markFailed(String error) {
        this.retryCount++;
        this.lastError = error;
        this.nextRetryAt = calculateNextRetryTime();
    }

    private LocalDateTime calculateNextRetryTime() {
        // Exponential backoff: 2^retryCount minutes, max 60 minutes
        long delayMinutes = Math.min(60, (long) Math.pow(2, retryCount));
        return LocalDateTime.now().plusMinutes(delayMinutes);
    }
}

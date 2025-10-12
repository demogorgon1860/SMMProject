package com.smmpanel.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
        name = "order_events",
        indexes = {
            @Index(name = "idx_order_event_order_id", columnList = "order_id"),
            @Index(name = "idx_order_event_type", columnList = "event_type"),
            @Index(name = "idx_order_event_timestamp", columnList = "event_timestamp"),
            @Index(name = "idx_order_event_aggregate", columnList = "aggregate_id"),
            @Index(name = "idx_order_event_sequence", columnList = "sequence_number"),
            @Index(name = "idx_order_event_processed", columnList = "is_processed")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 100)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    @Builder.Default
    private Integer eventVersion = 1;

    @Column(name = "sequence_number", nullable = false)
    private Long sequenceNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", columnDefinition = "jsonb")
    private Map<String, Object> eventData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 100)
    private String username;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "causation_id", length = 100)
    private String causationId;

    @CreationTimestamp
    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private LocalDateTime eventTimestamp;

    @Column(name = "is_processed", nullable = false)
    @Builder.Default
    private boolean isProcessed = false;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "kafka_topic", length = 100)
    private String kafkaTopic;

    @Column(name = "kafka_partition")
    private Integer kafkaPartition;

    @Column(name = "kafka_offset")
    private Long kafkaOffset;

    // Event types as constants
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_VALIDATED = "ORDER_VALIDATED";
    public static final String ORDER_PAID = "ORDER_PAID";
    public static final String ORDER_PROCESSING_STARTED = "ORDER_PROCESSING_STARTED";
    public static final String ORDER_COMPLETED = "ORDER_COMPLETED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_REFUNDED = "ORDER_REFUNDED";
    public static final String ORDER_FAILED = "ORDER_FAILED";
    public static final String ORDER_UPDATED = "ORDER_UPDATED";
    public static final String ORDER_STATUS_CHANGED = "ORDER_STATUS_CHANGED";

    public void markAsProcessed() {
        this.isProcessed = true;
        this.processedAt = LocalDateTime.now();
    }

    public void incrementRetryCount() {
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
        this.retryCount++;
    }
}

package com.smmpanel.entity;

import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

@Data
@Entity
@BatchSize(size = 20)
@Table(
        name = "orders",
        indexes = {
            @Index(name = "idx_orders_user_id", columnList = "user_id"),
            @Index(name = "idx_orders_service_id", columnList = "service_id"),
            // idx_orders_status created by Liquibase migration
            // @Index(name = "idx_orders_status", columnList = "status"),
            @Index(name = "idx_orders_created_at", columnList = "created_at"),
            @Index(name = "idx_orders_youtube_video_id", columnList = "youtube_video_id"),
            // Composite index for frequent queries
            @Index(
                    name = "idx_orders_user_status_created",
                    columnList = "user_id, status, created_at")
            // idx_orders_order_id created by Liquibase migration
            // @Index(name = "idx_orders_order_id", columnList = "order_id")
        })
@EqualsAndHashCode(callSuper = false)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @BatchSize(size = 25)
    @Fetch(FetchMode.SELECT)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    @BatchSize(size = 25)
    @Fetch(FetchMode.SELECT)
    private Service service;

    @Column(nullable = false, length = 500)
    private String link;

    @Column(nullable = false)
    private Integer quantity;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal charge;

    @Column(name = "start_count")
    @Builder.Default
    private Integer startCount = 0;

    private Integer remains;

    @Enumerated(EnumType.STRING)
    @Type(value = PostgreSQLEnumType.class)
    @Column(name = "status", nullable = false, columnDefinition = "order_status")
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "youtube_video_id", length = 100)
    private String youtubeVideoId;

    @Column(name = "target_views")
    private Integer targetViews;

    @Column(name = "coefficient", precision = 5, scale = 2)
    private BigDecimal coefficient;

    @Column(name = "target_country", length = 10)
    private String targetCountry;

    @Column(name = "order_id", unique = true, length = 50)
    private String orderId;

    // BinomCampaigns relationship removed - using dynamic campaign connections

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private VideoProcessing videoProcessing;

    @Column(name = "processing_priority")
    @Builder.Default
    private Integer processingPriority = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ERROR RECOVERY TRACKING FIELDS
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries")
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "last_error_type", length = 100)
    private String lastErrorType;

    @Column(name = "last_retry_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime lastRetryAt;

    @Column(name = "next_retry_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime nextRetryAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "error_stack_trace", columnDefinition = "TEXT")
    private String errorStackTrace;

    @Column(name = "failed_phase", length = 50)
    private String failedPhase;

    @Column(name = "is_manually_failed")
    @Builder.Default
    private Boolean isManuallyFailed = false;

    @Column(name = "operator_notes", columnDefinition = "TEXT")
    private String operatorNotes;

    /** User-specific order number (1, 2, 3... per user) */
    @Column(name = "user_order_number")
    private Integer userOrderNumber;

    // ========= NEW BINOM TRACKING FIELDS =========
    // binomCampaignId removed - using direct campaign connections via binomOfferId

    @Column(name = "binom_offer_id", length = 50)
    private String binomOfferId;

    @Column(name = "traffic_status", length = 20)
    @Builder.Default
    private String trafficStatus = "PENDING";

    @Column(name = "views_delivered")
    @Builder.Default
    private Integer viewsDelivered = 0;

    @Column(name = "cost_incurred", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal costIncurred = BigDecimal.ZERO;

    @Column(name = "budget_limit", precision = 10, scale = 2)
    private BigDecimal budgetLimit;

    /** Refill support - indicates if this order is a refill of another order */
    @Column(name = "is_refill", nullable = false)
    @Builder.Default
    private Boolean isRefill = false;

    /** If this is a refill order, points to the original order ID */
    @Column(name = "refill_parent_id")
    private Long refillParentId;

    // ========= INSTAGRAM BOT TRACKING FIELDS =========

    /** Instagram bot's internal order ID for correlation with webhooks */
    @Column(name = "instagram_bot_order_id", length = 64)
    private String instagramBotOrderId;

    /** Initial like count when order was created (for verification) */
    @Column(name = "start_like_count")
    @Builder.Default
    private Integer startLikeCount = 0;

    /** Initial follower count when order was created */
    @Column(name = "start_follower_count")
    @Builder.Default
    private Integer startFollowerCount = 0;

    /** Initial comment count when order was created */
    @Column(name = "start_comment_count")
    @Builder.Default
    private Integer startCommentCount = 0;

    /** Current like count (updated from webhook) */
    @Column(name = "current_like_count")
    @Builder.Default
    private Integer currentLikeCount = 0;

    /** Current follower count (updated from webhook) */
    @Column(name = "current_follower_count")
    @Builder.Default
    private Integer currentFollowerCount = 0;

    /** Current comment count (updated from webhook) */
    @Column(name = "current_comment_count")
    @Builder.Default
    private Integer currentCommentCount = 0;

    /** Custom comments for Instagram comment orders (one per line, bot picks randomly) */
    @Column(name = "custom_comments", columnDefinition = "TEXT")
    private String customComments;

    /**
     * Optimistic locking version counter - incremented on each update Prevents concurrent
     * modification issues during order processing
     */
    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(
            name = "created_at",
            updatable = false,
            nullable = false,
            columnDefinition = "TIMESTAMP")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getLink() {
        return link;
    }

    // setBinomCampaign method removed - using dynamic campaign connections
}

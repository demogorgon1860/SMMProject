package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "orders", indexes = {
    @Index(name = "idx_orders_user_id", columnList = "user_id"),
    @Index(name = "idx_orders_service_id", columnList = "service_id"),
    @Index(name = "idx_orders_status", columnList = "status"),
    @Index(name = "idx_orders_created_at", columnList = "created_at"),
    @Index(name = "idx_orders_youtube_video_id", columnList = "youtube_video_id"),
    @Index(name = "idx_orders_order_id", columnList = "order_id")
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
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
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

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @BatchSize(size = 10)
    private List<BinomCampaign> binomCampaigns;

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

    @Column(name = "last_retry_at")
    private LocalDateTime lastRetryAt;

    @Column(name = "next_retry_at")
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

    /**
     * Optimistic locking version counter - incremented on each update
     * Prevents concurrent modification issues during order processing
     */
    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public String getLink() {
        return link;
    }

    /**
     * Convenience method to add a BinomCampaign to the order
     * This method adds the campaign to the binomCampaigns list and sets the bidirectional relationship
     */
    public void setBinomCampaign(BinomCampaign campaign) {
        if (this.binomCampaigns == null) {
            this.binomCampaigns = new java.util.ArrayList<>();
        }
        
        // Remove any existing campaign first (if we want single campaign per order)
        this.binomCampaigns.clear();
        
        // Add the new campaign
        this.binomCampaigns.add(campaign);
        
        // Set bidirectional relationship
        if (campaign != null) {
            campaign.setOrder(this);
        }
    }
}
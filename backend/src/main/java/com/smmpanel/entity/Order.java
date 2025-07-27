package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private BinomCampaign binomCampaign;

    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<BinomCampaign> binomCampaigns;

    @OneToOne(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private VideoProcessing videoProcessing;

    @Column(name = "processing_priority")
    @Builder.Default
    private Integer processingPriority = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

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
}
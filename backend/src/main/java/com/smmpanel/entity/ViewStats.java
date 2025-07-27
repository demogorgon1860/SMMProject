package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "view_stats", indexes = {
    @Index(name = "idx_view_stats_order_id", columnList = "order_id"),
    @Index(name = "idx_view_stats_video_processing_id", columnList = "video_processing_id"),
    @Index(name = "idx_view_stats_created_at", columnList = "created_at"),
    @Index(name = "idx_view_stats_last_checked", columnList = "last_checked")
})
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class ViewStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "order_id", referencedColumnName = "id"),
        @JoinColumn(name = "order_created_at", referencedColumnName = "created_at")
    })
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_processing_id")
    private VideoProcessing videoProcessing;

    @Column(name = "current_views")
    private Integer currentViews = 0;

    @Column(name = "target_views", nullable = false)
    private Integer targetViews;

    @Column(name = "views_velocity", precision = 10, scale = 2)
    private BigDecimal viewsVelocity;

    @Column(name = "last_checked")
    private LocalDateTime lastChecked = LocalDateTime.now();

    @Column(name = "check_interval")
    private Integer checkInterval = 1800;

    @Column(name = "check_count")
    private Integer checkCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
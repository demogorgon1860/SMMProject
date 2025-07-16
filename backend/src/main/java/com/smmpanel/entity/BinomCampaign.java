package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "binom_campaigns")
@EqualsAndHashCode(callSuper = false)
public class BinomCampaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "order_id", referencedColumnName = "id"),
        @JoinColumn(name = "order_created_at", referencedColumnName = "created_at")
    })
    private Order order;

    @Column(name = "campaign_id", unique = true, nullable = false, length = 100)
    private String campaignId;

    @Column(name = "offer_id", length = 100)
    private String offerId;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "traffic_source_id")
    private TrafficSource trafficSource;

    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal coefficient;

    @Column(name = "clicks_required", nullable = false)
    private Integer clicksRequired;

    @Column(name = "clicks_delivered")
    private Integer clicksDelivered = 0;

    @Column(name = "views_generated")
    private Integer viewsGenerated = 0;

    @Column(length = 50)
    private String status = "ACTIVE";

    @Column(name = "cost_per_click", precision = 8, scale = 6)
    private BigDecimal costPerClick;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
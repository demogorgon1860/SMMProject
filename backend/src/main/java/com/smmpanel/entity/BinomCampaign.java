package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity для хранения информации о кампаниях Binom
 */
@Data
@Entity
@Table(name = "binom_campaigns")
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class BinomCampaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "campaign_id", unique = true, nullable = false, length = 100)
    private String campaignId;

    @Column(name = "campaign_name")
    private String campaignName;

    @Column(name = "offer_id", length = 100)
    private String offerId;

    @Column(name = "offer_name")
    private String offerName;

    @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "traffic_source_id")
    private TrafficSource trafficSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixed_campaign_id")
    private FixedBinomCampaign fixedCampaign;

    @Column(precision = 4, scale = 2)
    private BigDecimal coefficient;

    @Column(name = "clicks_required")
    private Integer clicksRequired;

    @Column(name = "clicks_delivered")
    private Integer clicksDelivered = 0;

    @Column(name = "views_generated")
    private Integer viewsGenerated = 0;

    @Column(name = "conversions")
    private Integer conversions;

    @Column(precision = 10, scale = 2)
    private BigDecimal cost;

    @Column(precision = 10, scale = 2)
    private BigDecimal revenue;

    @Column(name = "last_stats_update")
    private LocalDateTime lastStatsUpdate;

    @Column(length = 50)
    private String status = "ACTIVE";

    @Column(name = "cost_per_click", precision = 8, scale = 6)
    private BigDecimal costPerClick;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "geo_targeting", length = 50)
    private String geoTargeting = "US";

    @Column
    private Integer priority;

    @Column(name = "is_fixed_campaign")
    @Builder.Default
    private Boolean isFixedCampaign = false;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void setActive(boolean active) {
        this.isActive = active;
    }
}
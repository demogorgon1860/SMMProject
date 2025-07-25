package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "fixed_binom_campaigns")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixedBinomCampaign {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", unique = true, nullable = false, length = 100)
    private String campaignId;

    @Column(name = "campaign_name", nullable = false)
    private String campaignName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "traffic_source_id", nullable = false)
    private TrafficSource trafficSource;

    @Column(name = "geo_targeting", length = 50)
    @Builder.Default
    private String geoTargeting = "US";

    @Column
    @Builder.Default
    private Integer weight = 100;

    @Column
    @Builder.Default
    private Integer priority = 1;

    @Column
    @Builder.Default
    private Boolean active = true;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

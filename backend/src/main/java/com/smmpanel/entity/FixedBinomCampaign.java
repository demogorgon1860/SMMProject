package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "fixed_binom_campaigns", indexes = {
    @Index(name = "idx_fixed_binom_campaigns_campaign_id", columnList = "campaign_id"),
    @Index(name = "idx_fixed_binom_campaigns_active", columnList = "active"),
    @Index(name = "idx_fixed_binom_campaigns_created_at", columnList = "created_at")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class FixedBinomCampaign {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", unique = true, nullable = false, length = 100)
    private String campaignId;

    @Column(name = "campaign_name", nullable = false)
    private String campaignName;



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

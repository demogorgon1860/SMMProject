package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "traffic_sources", indexes = {
    @Index(name = "idx_traffic_sources_source_id", columnList = "source_id"),
    @Index(name = "idx_traffic_sources_active", columnList = "active"),
    @Index(name = "idx_traffic_sources_quality_level", columnList = "quality_level"),
    @Index(name = "idx_traffic_sources_created_at", columnList = "created_at")
})
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class TrafficSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "source_id", unique = true, nullable = false, length = 100)
    private String sourceId; // ID in Binom

    @Column(nullable = false)
    private Integer weight = 1;

    @Column(name = "daily_limit")
    private Integer dailyLimit;

    @Column(name = "clicks_used_today")
    private Integer clicksUsedToday = 0;

    @Column(name = "last_reset_date")
    private LocalDate lastResetDate = LocalDate.now();

    @Column(name = "geo_targeting")
    private String geoTargeting;
    
    @Column(name = "quality_level", nullable = false)
    private String qualityLevel = "STANDARD"; // STANDARD, PREMIUM, HIGH_QUALITY

    private Boolean active = true;

    @Column(name = "performance_score", precision = 5, scale = 2)
    private BigDecimal performanceScore = new BigDecimal("100.00");

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
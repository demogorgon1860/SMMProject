package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "services", indexes = {
    @Index(name = "idx_services_active", columnList = "active"),
    @Index(name = "idx_services_category", columnList = "category"),
    @Index(name = "idx_services_geo_targeting", columnList = "geo_targeting"),
    @Index(name = "idx_services_created_at", columnList = "created_at")
})
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class Service {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 100)
    private String category;

    @Column(name = "min_order")
    private Integer minOrder;

    @Column(name = "max_order")
    private Integer maxOrder;

    @Column(name = "price_per_1000", precision = 10, scale = 6)
    private BigDecimal pricePer1000;

    @Column(name = "start_count_100")
    private Integer startCount100;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    private Boolean active = true;

    @Column(name = "geo_targeting", length = 50)
    @Builder.Default
    private String geoTargeting = "US";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
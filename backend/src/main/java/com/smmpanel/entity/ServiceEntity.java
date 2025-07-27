package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "service_entities", indexes = {
    @Index(name = "idx_service_entities_active", columnList = "active"),
    @Index(name = "idx_service_entities_category_id", columnList = "category_id"),
    @Index(name = "idx_service_entities_service_type", columnList = "service_type")
})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "service_type", nullable = false, length = 100)
    private String serviceType;

    @Column(precision = 10, scale = 6)
    private BigDecimal price;

    @Column(name = "min_order")
    private Integer minOrder;

    @Column(name = "max_order")
    private Integer maxOrder;

    @Builder.Default
    private Boolean active = true;

    @Column(name = "conversion_coefficient", precision = 5, scale = 2)
    private BigDecimal conversionCoefficient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
} 
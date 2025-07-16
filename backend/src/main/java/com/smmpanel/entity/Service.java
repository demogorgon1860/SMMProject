package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "services")
public class Service {
    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(name = "min_order", nullable = false)
    private Integer minOrder;

    @Column(name = "max_order", nullable = false)
    private Integer maxOrder;

    @Column(name = "price_per_1000", precision = 8, scale = 4, nullable = false)
    private BigDecimal pricePer1000;

    @Column(columnDefinition = "TEXT")
    private String description;

    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
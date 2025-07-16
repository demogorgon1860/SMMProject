package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversion_coefficients")
@EqualsAndHashCode(callSuper = false)
public class ConversionCoefficient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "with_clip", precision = 4, scale = 2)
    private BigDecimal withClip = new BigDecimal("3.0");

    @Column(name = "without_clip", precision = 4, scale = 2)
    private BigDecimal withoutClip = new BigDecimal("4.0");

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
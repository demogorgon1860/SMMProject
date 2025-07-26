package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "conversion_coefficients", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"service_id", "without_clip"}))
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ConversionCoefficient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "coefficient", precision = 4, scale = 2, nullable = false)
    private BigDecimal coefficient;

    @Column(name = "without_clip", nullable = false)
    private BigDecimal withoutClip;

    @Column(name = "with_clip", nullable = false)
    private BigDecimal withClip;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Helper methods for backward compatibility
    public void setWithoutClip(Boolean withoutClip) {
        this.withoutClip = withoutClip ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    public Boolean getWithoutClipAsBoolean() {
        return withoutClip != null && withoutClip.compareTo(BigDecimal.ZERO) > 0;
    }
}
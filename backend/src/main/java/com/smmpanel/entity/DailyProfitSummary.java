package com.smmpanel.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "daily_profit_summary")
public class DailyProfitSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_date", nullable = false, unique = true)
    private LocalDate reportDate;

    @Column(name = "total_profit", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal totalProfit = BigDecimal.ZERO;

    @Column(name = "completed_count", nullable = false)
    @Builder.Default
    private Integer completedCount = 0;

    @Column(name = "partial_count", nullable = false)
    @Builder.Default
    private Integer partialCount = 0;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}

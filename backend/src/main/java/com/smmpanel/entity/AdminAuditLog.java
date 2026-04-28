package com.smmpanel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Lightweight admin-action feed row. See {@code v2026.04-add-admin-audit-log.xml} for the why.
 *
 * <p>This is the operator-facing trail: human-readable summaries of clicks an admin made in the
 * panel UI. Distinct from {@link AuditLog} (the comprehensive compliance trail with 7-year
 * retention and rich enrichment).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "admin_audit_log",
        indexes = {
            @Index(name = "idx_aal_created_at_desc", columnList = "created_at DESC"),
            @Index(name = "idx_aal_admin_created", columnList = "admin_id, created_at DESC")
        })
public class AdminAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "admin_username", length = 100)
    private String adminUsername;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "target_label", length = 255)
    private String targetLabel;

    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

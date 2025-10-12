package com.smmpanel.entity;

import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import org.hibernate.type.SqlTypes;

/**
 * Comprehensive Audit Log Entity for tracking all system changes Based on Stack Overflow best
 * practices for audit logging Reference:
 * https://stackoverflow.com/questions/29332907/audit-trail-with-spring-boot-jpa
 */
@Entity
@Table(
        name = "audit_logs",
        indexes = {
            @Index(name = "idx_audit_entity_type_id", columnList = "entity_type, entity_id"),
            @Index(name = "idx_audit_user_id", columnList = "user_id"),
            @Index(name = "idx_audit_action", columnList = "action"),
            @Index(name = "idx_audit_timestamp", columnList = "timestamp"),
            @Index(name = "idx_audit_category", columnList = "category"),
            @Index(name = "idx_audit_severity", columnList = "severity"),
            @Index(name = "idx_audit_ip_address", columnList = "ip_address")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Entity Information
    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType; // ORDER, PAYMENT, USER, etc.

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "entity_identifier", length = 100)
    private String entityIdentifier; // Alternative identifier (order_id, payment_id, etc.)

    // Action Information
    @Column(name = "action", nullable = false, length = 100)
    private String action; // CREATE, UPDATE, DELETE, PAYMENT_INITIATED, etc.

    @Enumerated(EnumType.STRING)
    @Type(value = PostgreSQLEnumType.class)
    @Column(
            name = "category",
            nullable = false,
            columnDefinition = "audit_category DEFAULT 'GENERAL'")
    @Builder.Default
    private AuditCategory category = AuditCategory.GENERAL;

    @Enumerated(EnumType.STRING)
    @Type(value = PostgreSQLEnumType.class)
    @Column(name = "severity", nullable = false, columnDefinition = "audit_severity DEFAULT 'INFO'")
    @Builder.Default
    private AuditSeverity severity = AuditSeverity.INFO;

    // User Information
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 100)
    private String username;

    @Column(name = "user_role", length = 50)
    private String userRole;

    // Change Details
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changes", columnDefinition = "jsonb")
    private Map<String, Object> changes; // Detailed changes in JSON format

    @Column(name = "description", length = 500)
    private String description;

    // Request Information
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Column(name = "api_key_used", length = 100)
    private String apiKeyUsed;

    // Payment Specific Fields
    @Column(name = "payment_amount", precision = 12, scale = 2)
    private java.math.BigDecimal paymentAmount;

    @Column(name = "payment_currency", length = 10)
    private String paymentCurrency;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "payment_provider", length = 50)
    private String paymentProvider;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "payment_status", length = 50)
    private String paymentStatus;

    // Security Fields
    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "security_flags", length = 500)
    private String securityFlags;

    @Column(name = "is_suspicious")
    @Builder.Default
    private Boolean isSuspicious = false;

    // Metadata
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @CreationTimestamp
    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "execution_time_ms")
    private Long executionTimeMs;

    // Compliance Fields
    @Column(name = "compliance_checked")
    @Builder.Default
    private Boolean complianceChecked = false;

    @Column(name = "retention_days")
    @Builder.Default
    private Integer retentionDays = 2555; // 7 years default for financial records

    @Column(name = "is_pii_redacted")
    @Builder.Default
    private Boolean isPiiRedacted = false;

    // Enums
    public enum AuditCategory {
        GENERAL,
        AUTHENTICATION,
        AUTHORIZATION,
        PAYMENT,
        ORDER,
        USER_MANAGEMENT,
        SYSTEM,
        SECURITY,
        CONFIGURATION,
        DATA_ACCESS,
        API,
        COMPLIANCE
    }

    public enum AuditSeverity {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        CRITICAL,
        SECURITY_ALERT
    }

    // Helper methods
    public void addChange(String field, Object oldValue, Object newValue) {
        if (this.changes == null) {
            this.changes = new java.util.HashMap<>();
        }
        this.changes.put(
                field,
                Map.of(
                        "old", oldValue != null ? oldValue : "null",
                        "new", newValue != null ? newValue : "null",
                        "changed_at", LocalDateTime.now()));
    }

    public void addMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new java.util.HashMap<>();
        }
        this.metadata.put(key, value);
    }

    public void markAsSuspicious(String reason) {
        this.isSuspicious = true;
        this.securityFlags = (this.securityFlags != null ? this.securityFlags + ", " : "") + reason;
        this.severity = AuditSeverity.SECURITY_ALERT;
    }
}

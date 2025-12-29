package com.smmpanel.entity;

import io.hypersistence.utils.hibernate.type.basic.PostgreSQLEnumType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

@Data
@Entity
@Table(
        name = "balance_transactions",
        indexes = {
            @Index(name = "idx_balance_transactions_user_id", columnList = "user_id"),
            @Index(name = "idx_balance_transactions_order_id", columnList = "order_id"),
            @Index(name = "idx_balance_transactions_deposit_id", columnList = "deposit_id"),
            @Index(name = "idx_balance_transactions_type", columnList = "transaction_type"),
            @Index(name = "idx_balance_transactions_created_at", columnList = "created_at"),
            @Index(name = "idx_balance_transactions_transaction_id", columnList = "transaction_id")
        })
@EqualsAndHashCode(callSuper = false)
@Slf4j
public class BalanceTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "order_id", referencedColumnName = "id"),
        @JoinColumn(name = "order_created_at", referencedColumnName = "created_at")
    })
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deposit_id")
    private BalanceDeposit deposit;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_before", nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", nullable = false, precision = 10, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "transaction_id", unique = true, length = 100)
    private String transactionId;

    @Column(name = "reference_id", length = 255)
    private String referenceId;

    @Column(name = "source_system", length = 50)
    private String sourceSystem;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Type(value = PostgreSQLEnumType.class)
    @Column(name = "transaction_type", nullable = false, columnDefinition = "transaction_type")
    private TransactionType transactionType;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Optimistic locking version counter - incremented on each update Prevents concurrent
     * modification issues during transaction processing
     */
    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    @Column(name = "reconciliation_status")
    @Enumerated(EnumType.STRING)
    private ReconciliationStatus reconciliationStatus;

    @Column(name = "audit_hash", length = 64)
    private String auditHash;

    @Column(name = "previous_transaction_hash", length = 64)
    private String previousTransactionHash;

    @PrePersist
    private void prePersist() {
        if (transactionId == null) {
            transactionId =
                    "TXN-"
                            + UUID.randomUUID()
                                    .toString()
                                    .replace("-", "")
                                    .substring(0, 16)
                                    .toUpperCase();
        }
        processedAt = LocalDateTime.now();
        reconciliationStatus = ReconciliationStatus.PENDING;
        generateAuditHash();
    }

    @PreUpdate
    private void preUpdate() {
        generateAuditHash();
    }

    private void generateAuditHash() {
        // Generate hash for integrity verification
        String dataToHash =
                String.format(
                        "%s|%s|%s|%s|%s|%s|%s",
                        user != null ? user.getId() : "null",
                        amount != null ? amount.toString() : "null",
                        balanceBefore != null ? balanceBefore.toString() : "null",
                        balanceAfter != null ? balanceAfter.toString() : "null",
                        transactionType != null ? transactionType.toString() : "null",
                        createdAt != null ? createdAt.toString() : "null",
                        transactionId != null ? transactionId : "null");

        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(dataToHash.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            this.auditHash = hexString.toString();
        } catch (Exception e) {
            log.error("Failed to generate audit hash for transaction {}", transactionId, e);
        }
    }

    public enum ReconciliationStatus {
        PENDING,
        RECONCILED,
        DISCREPANCY,
        UNDER_REVIEW,
        RESOLVED
    }
}

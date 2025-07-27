package com.smmpanel.entity;

import com.smmpanel.entity.TransactionType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "balance_transactions", indexes = {
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
    @JoinColumn(name = "order_id")
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

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
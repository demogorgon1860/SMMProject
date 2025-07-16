package com.smmpanel.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "balance_deposits")
@EqualsAndHashCode(callSuper = false)
public class BalanceDeposit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "order_id", unique = true, nullable = false)
    private String orderId;

    @Column(name = "amount_usd", nullable = false, precision = 10, scale = 2)
    private BigDecimal amountUsd;

    @Column(length = 10)
    private String currency;

    @Column(name = "crypto_amount", precision = 20, scale = 8)
    private BigDecimal cryptoAmount;

    @Column(name = "cryptomus_payment_id")
    private String cryptomusPaymentId;

    @Column(name = "payment_url", length = 500)
    private String paymentUrl;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "webhook_data", columnDefinition = "JSONB")
    private String webhookData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
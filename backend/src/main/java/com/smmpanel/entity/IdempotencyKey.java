package com.smmpanel.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stripe-style idempotency record. Backs {@link
 * com.smmpanel.service.core.IdempotencyService#executeWithKey} — when a client retries a request
 * with the same {@code Idempotency-Key} header, the cached response is returned instead of
 * executing the action twice. The unique constraint on {@code (user_id, idempotency_key,
 * operation)} is the gate; the service translates the constraint violation into a "fetch and
 * replay" path.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "idempotency_keys",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_idem_user_key_op",
                        columnNames = {"user_id", "idempotency_key", "operation"}))
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "operation", nullable = false, length = 64)
    private String operation;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    /**
     * SHA-256 of the canonical request body. Reusing the same key with a different body is a client
     * bug — the service rejects it with HTTP 422 instead of silently returning an answer that
     * doesn't match the new request.
     */
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}

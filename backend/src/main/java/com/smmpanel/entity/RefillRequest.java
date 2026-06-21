package com.smmpanel.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/**
 * User-initiated request for a free post-completion refill on an order. The actual refill is
 * created by {@code OrderRefillService.createRefill} once an admin approves this request.
 *
 * <p>This entity captures only the <em>request</em> state machine; the resulting refill order +
 * tracking record live in {@link OrderRefill}, with FK back here via {@link #refillId} / {@link
 * #refillOrderId} for audit traceability.
 */
@Entity
@Table(
        name = "refill_requests",
        indexes = {
            @Index(name = "idx_rr_user_created", columnList = "user_id, created_at DESC"),
            @Index(name = "idx_rr_order", columnList = "order_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefillRequest {

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    /** Optional context the user supplied when creating the request. */
    @Column(name = "user_note", length = 500)
    private String userNote;

    /** Required when status = REJECTED. Free text explaining why. */
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /** Admin (or operator) who decided. Null until the request is approved/rejected. */
    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    /** {@code OrderRefill.id} produced by approval. Null until APPROVED. */
    @Column(name = "refill_id")
    private Long refillId;

    /** {@code Order.id} of the new refill order. Null until APPROVED. */
    @Column(name = "refill_order_id")
    private Long refillOrderId;

    // ---- Bot drop-check snapshot, copied from the latest DONE RefillCheck at request creation.
    // Nullable: requests made without a prior check (and legacy rows) keep NULLs. Drives drop-based
    // approval (bot_refill_needed becomes the refill quantity) and admin-queue display. ----

    /** Bot-checked dropped amount to re-deliver. Used as the refill quantity on approval. */
    @Column(name = "bot_refill_needed")
    private Integer botRefillNeeded;

    @Column(name = "bot_dropped")
    private Integer botDropped;

    @Column(name = "bot_drop_rate")
    private BigDecimal botDropRate;

    @Column(name = "bot_current_count")
    private Integer botCurrentCount;

    /** True when the bot's scan stopped early — the dropped amount is a conservative estimate. */
    @Column(name = "bot_early_stopped")
    private Boolean botEarlyStopped;

    @Column(name = "bot_check_id")
    private Long botCheckId;

    @Column(name = "bot_checked_at")
    private LocalDateTime botCheckedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

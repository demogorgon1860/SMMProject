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
 *
 * <p><b>Auto-check flow (current):</b> a request is born {@link Status#CHECKING} — the panel runs
 * the bot drop-check out-of-band ({@code RefillRequestAutoScheduler}) and finalizes it:
 *
 * <ul>
 *   <li>drop &gt; 0 → {@link Status#PENDING} (lands in the admin queue, carrying the real dropped
 *       amount; approval re-delivers exactly that).
 *   <li>drop == 0 → {@link Status#NO_DROP} (auto-closed, nothing to refill).
 *   <li>check couldn't complete after the retry budget → {@link Status#FAILED} (user may resubmit).
 * </ul>
 *
 * The admin only ever acts on {@link Status#PENDING}: {@link Status#APPROVED} / {@link
 * Status#REJECTED}.
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
        /** System is running the automatic drop-check; not yet visible to the admin. */
        CHECKING,
        /** Drop confirmed (&gt; 0) — awaiting admin approval, sized to the dropped amount. */
        PENDING,
        APPROVED,
        REJECTED,
        /** Auto-check finished with zero drop — nothing to refill (terminal, auto-closed). */
        NO_DROP,
        /** Auto-check could not complete after the retry budget (terminal; user may resubmit). */
        FAILED
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

    /**
     * The drop-check this request is currently bound to. While {@link Status#CHECKING} this points
     * at the in-flight ({@code RUNNING}) check; once finalized it points at the {@code DONE} check
     * whose snapshot was copied onto the request.
     */
    @Column(name = "bot_check_id")
    private Long botCheckId;

    @Column(name = "bot_checked_at")
    private LocalDateTime botCheckedAt;

    /**
     * How many times the auto-scheduler has kicked off a bot drop-check for this request. Bounds the
     * retry budget so a permanently-unreachable check eventually transitions to {@link
     * Status#FAILED} instead of looping forever.
     */
    @Column(name = "check_attempts", nullable = false)
    @Builder.Default
    private Integer checkAttempts = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

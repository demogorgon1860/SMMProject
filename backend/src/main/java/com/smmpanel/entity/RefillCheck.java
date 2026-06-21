package com.smmpanel.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Durable record of a live "drop check" against the Instagram bot ({@code POST /api/refill/check} →
 * poll {@code GET /api/refill/status}). The bot's own job store is memory-only and lost on restart,
 * so the panel persists the outcome here: it is the source of truth for the Refill page, survives
 * bot restarts, and is polled out-of-band by {@code RefillCheckScheduler} (never inside an HTTP
 * request or the admin approve() transaction — a bot check can take minutes).
 *
 * <p>v1 is single-action only (like OR follow OR comment), so exactly one bot report maps to one
 * row. {@link #dropRate} is computed panel-side as {@code refill_needed / ordered_count * 100}.
 */
@Entity
@Table(
        name = "refill_checks",
        indexes = {
            @Index(name = "idx_rc_order_created", columnList = "order_id, requested_at DESC"),
            @Index(name = "idx_rc_user_created", columnList = "user_id, requested_at DESC")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefillCheck {

    public enum Status {
        RUNNING,
        DONE,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Bot job id ({@code rf_...}) and the instance that answered, for pinned status polling. */
    @Column(name = "bot_job_id", length = 64)
    private String botJobId;

    @Column(name = "bot_instance_url", length = 255)
    private String botInstanceUrl;

    /** like | follow | comment (single-action v1). */
    @Column(name = "action_type", length = 20)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.RUNNING;

    // ---- Result snapshot (populated when status -> DONE) ----

    @Column(name = "ordered_count")
    private Integer orderedCount;

    @Column(name = "delivered")
    private Integer delivered;

    @Column(name = "matchable")
    private Integer matchable;

    @Column(name = "present")
    private Integer present;

    @Column(name = "dropped")
    private Integer dropped;

    @Column(name = "refill_needed")
    private Integer refillNeeded;

    @Column(name = "current_count")
    private Integer currentCount;

    @Column(name = "drop_rate")
    private BigDecimal dropRate;

    @Column(name = "early_stopped", nullable = false)
    @Builder.Default
    private Boolean earlyStopped = Boolean.FALSE;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "error", length = 500)
    private String error;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;
}

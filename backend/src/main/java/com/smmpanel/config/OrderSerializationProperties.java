package com.smmpanel.config;

import com.smmpanel.entity.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Metric-aware per-URL order serialization. When several orders target the SAME (normalized) link,
 * ones whose affected metrics OVERLAP (like/comment/follow) are dispatched one-at-a-time — one
 * IN_PROGRESS, the rest waiting in PENDING (lowest id = FIFO), released as the active one reaches a
 * terminal state — so the bot's start-count scout never reads a baseline another order is mutating.
 * Orders on independent metrics (e.g. likes vs comments) run in parallel.
 *
 * <p>When {@code enabled=false} the panel falls back to immediate dispatch (zero behavioral
 * change), so this is a safe runtime kill-switch.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.order.serialization")
public class OrderSerializationProperties {

    /** Master switch. False ⇒ dispatch immediately on the Kafka event (legacy behavior). */
    private boolean enabled = true;

    /**
     * Statuses that mean an order currently "occupies" its URL (dispatched, not yet terminal).
     * While any order for a link is in one of these, no other same-link order is dispatched.
     * PENDING is NOT here (those are the waiters); terminal statuses (COMPLETED/PARTIAL/CANCELLED)
     * are not here (they free the URL). Comma-separated env override binds to this list.
     */
    private List<OrderStatus> activeStatuses =
            new ArrayList<>(
                    List.of(
                            OrderStatus.IN_PROGRESS,
                            OrderStatus.PROCESSING,
                            OrderStatus.ACTIVE,
                            OrderStatus.PAUSED,
                            OrderStatus.HOLDING));

    /**
     * Safety-sweeper interval (ms). The sweeper is the authoritative backstop, not just an
     * optimization.
     */
    private long sweepIntervalMs = 60000;

    /** Max candidate links processed per sweep pass (bounds a backlog burst). */
    private int sweepBatchSize = 500;

    /**
     * Max PENDING waiters scanned per {@code pumpUrl} pass when picking which non-conflicting
     * orders to dispatch in parallel. A link rarely has more than a handful of waiters, and the
     * scan stops early once every metric (like/comment/follow) is occupied. A waiter beyond this
     * bound (only on a link with a huge same-metric backlog hiding a different-metric order) is
     * picked up by the next pump or the sweeper — no worse than the previous link-only
     * serialization.
     */
    private int dispatchScanLimit = 200;

    /**
     * An active order whose {@code updatedAt} is older than this is considered "stuck" (lost
     * webhook / crashed bot). If PENDING orders are waiting behind it, the sweeper alerts System
     * Health but does NOT auto-release (operator resolves manually — preserves start-count
     * correctness). Default is comfortably longer than the 4h PAUSED admin-decision window.
     */
    private int stuckActiveHours = 6;

    /**
     * Per-link cooldown (minutes) between stuck-order alerts so a wedged URL doesn't spam the
     * group.
     */
    private int stuckAlertCooldownMinutes = 60;
}

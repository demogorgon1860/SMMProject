package com.smmpanel.config;

import com.smmpanel.entity.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-URL order serialization. When several orders target the SAME (normalized) link, they are
 * dispatched to the bot one-at-a-time: one is IN_PROGRESS, the rest wait in PENDING, and the next
 * (lowest id = FIFO) is released only when the active one reaches a terminal state. This keeps the
 * bot's start-count scout from reading a baseline that another order is already mutating.
 *
 * <p>When {@code enabled=false} the panel falls back to today's immediate dispatch (zero behavioral
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

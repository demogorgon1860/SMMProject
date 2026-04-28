package com.smmpanel.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One queue snapshot for the {@code /admin/system} Queues tab.
 *
 * <p>Sourced primarily from the RabbitMQ HTTP Management API (full picture: depth, consumers,
 * deliver/ack rates, DLQ wiring). Falls back to {@link org.springframework.amqp.core.AmqpAdmin}
 * which can only report {@link #depth} and {@link #consumers} — the rate fields will be {@code -1}
 * in that fallback path so the UI can render "—" instead of misleading zeros.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueueStatsDto {

    private String name;

    /** Total messages in the queue (ready + unacked). */
    private long depth;

    /** Currently subscribed consumers. */
    private int consumers;

    /** Messages currently delivered but not yet acked. */
    private long unacked;

    /** Messages delivered to consumers per second (smoothed by RabbitMQ). -1 if unavailable. */
    private double deliverRate;

    /** Messages acked per second (smoothed). -1 if unavailable. */
    private double ackRate;

    /** Messages published per second (smoothed). -1 if unavailable. */
    private double publishRate;

    /** Length of the configured dead-letter queue, or -1 if this queue has none. */
    private long dlqDepth;

    /**
     * True if the queue is itself a DLQ — either name endswith ".dlq" / "dead", or RabbitMQ marks
     * it as the target of a dead-letter-exchange. The UI uses this to tint and to show Purge.
     */
    private boolean isDlq;
}

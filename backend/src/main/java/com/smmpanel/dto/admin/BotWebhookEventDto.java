package com.smmpanel.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snapshot of a webhook/result event arriving from the Instagram bot. Maintained as a Redis LIST
 * for the admin "Recent webhooks" view, and republished on a pub/sub channel for the live SSE
 * stream.
 *
 * <p>Severity is derived from {@code status} so the UI can color rows without re-deriving the
 * mapping client-side.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BotWebhookEventDto {

    /** ISO-8601 UTC timestamp when the panel received the event. */
    private String ts;

    /** Source: "webhook" (HTTP) or "rabbitmq" (queue consumer). */
    private String source;

    /** Panel order ID (external_id). May be null if the event is unparseable. */
    private String externalId;

    /** Bot's internal order ID (id). */
    private String botOrderId;

    /** Event name (e.g. "order.completed"). May be null for RabbitMQ results. */
    private String event;

    /** Status string from the bot (completed, failed, partial, pending_cancel, cancelled). */
    private String status;

    /** Items completed at the time of the event. */
    private Integer completed;

    /** Items failed at the time of the event. */
    private Integer failed;

    /** Optional human-readable detail (error message, etc). */
    private String message;

    /**
     * Severity bucket — "info" (progress), "success" (completed), "warn" (partial /
     * pending_cancel), "error" (failed / cancelled). Pre-computed for the UI.
     */
    private String severity;
}

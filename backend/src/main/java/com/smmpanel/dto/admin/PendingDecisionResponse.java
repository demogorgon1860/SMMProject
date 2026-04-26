package com.smmpanel.dto.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row in {@code GET /v2/admin/telegram/pending-decisions}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PendingDecisionResponse {
    private Long orderId;
    private String botOrderId;
    private Integer completed;
    private Integer quantity;
    private String orderStatusAtTime;
    private LocalDateTime createdAt;

    /** Milliseconds remaining before the Redis-backed decision auto-expires. */
    private long expiresInMs;

    /** Free-text reason recorded when the decision was created (best-effort, may be null). */
    private String reason;
}

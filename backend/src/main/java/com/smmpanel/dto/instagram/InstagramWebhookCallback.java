package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Webhook callback DTO received from Instagram bot when order completes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramWebhookCallback {

    /**
     * Event type (e.g., "order.completed")
     */
    private String event;

    /**
     * Bot's internal order ID
     */
    private String id;

    /**
     * Panel's order ID (external_id from request)
     */
    @JsonProperty("external_id")
    private String externalId;

    /**
     * Order status: completed or failed
     */
    private String status;

    /**
     * Number of successfully completed actions
     */
    private Integer completed;

    /**
     * Number of failed actions
     */
    private Integer failed;

    /**
     * Initial like count before order
     */
    @JsonProperty("start_like_count")
    private Integer startLikeCount;

    /**
     * Current like count after order
     */
    @JsonProperty("current_like_count")
    private Integer currentLikeCount;

    /**
     * Initial comment count before order
     */
    @JsonProperty("start_comment_count")
    private Integer startCommentCount;

    /**
     * Current comment count after order
     */
    @JsonProperty("current_comment_count")
    private Integer currentCommentCount;

    /**
     * Initial follower count before order
     */
    @JsonProperty("start_follower_count")
    private Integer startFollowerCount;

    /**
     * Current follower count after order
     */
    @JsonProperty("current_follower_count")
    private Integer currentFollowerCount;

    /**
     * Timestamp when order completed
     */
    @JsonProperty("completed_at")
    private String completedAt;
}

package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message format for Instagram order results received via RabbitMQ. This matches the format sent by
 * the Instagram bot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramResultMessage {

    /** Bot's internal order ID */
    @JsonProperty("order_id")
    private String orderId;

    /** Panel's order ID for correlation */
    @JsonProperty("external_id")
    private String externalId;

    /** Result status: completed, failed, partial */
    private String status;

    /** Number of successfully completed actions */
    private int completed;

    /** Number of failed actions */
    private int failed;

    /** Error message if status is failed */
    private String error;

    /** Initial like count before processing (for verification) */
    @JsonProperty("start_like_count")
    private Integer startLikeCount;

    /** Current like count after processing */
    @JsonProperty("current_like_count")
    private Integer currentLikeCount;

    /** Initial comment count before processing */
    @JsonProperty("start_comment_count")
    private Integer startCommentCount;

    /** Current comment count after processing */
    @JsonProperty("current_comment_count")
    private Integer currentCommentCount;

    /** Initial follower count before processing */
    @JsonProperty("start_follower_count")
    private Integer startFollowerCount;

    /** Current follower count after processing */
    @JsonProperty("current_follower_count")
    private Integer currentFollowerCount;

    /** Timestamp when order was completed */
    @JsonProperty("finished_at")
    private OffsetDateTime finishedAt;
}

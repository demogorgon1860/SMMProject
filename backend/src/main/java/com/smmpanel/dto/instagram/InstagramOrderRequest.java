package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating an order in the Instagram bot.
 * Maps to POST /api/orders/create endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramOrderRequest {

    /**
     * Order type: like, comment, follow, like_follow, like_comment, like_comment_follow
     */
    private String type;

    /**
     * Target Instagram URL (post or profile)
     */
    @JsonProperty("target_url")
    private String targetUrl;

    /**
     * Number of actions to perform
     */
    private Integer count;

    /**
     * External ID from panel for correlation
     */
    @JsonProperty("external_id")
    private String externalId;

    /**
     * Callback URL for order completion webhook
     */
    @JsonProperty("callback_url")
    private String callbackUrl;

    /**
     * Optional comment text for comment orders
     */
    @JsonProperty("comment_text")
    private String commentText;

    /**
     * Order priority (0 = normal)
     */
    @Builder.Default
    private Integer priority = 0;
}

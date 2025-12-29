package com.smmpanel.dto.instagram;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** DTO for Instagram order status from GET /api/orders/get endpoint. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramOrderStatus {

    private String id;

    private String type;

    @JsonProperty("target_url")
    private String targetUrl;

    private Integer count;

    @JsonProperty("external_id")
    private String externalId;

    private String status;

    private Integer completed;

    private Integer failed;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("started_at")
    private String startedAt;

    @JsonProperty("completed_at")
    private String completedAt;
}

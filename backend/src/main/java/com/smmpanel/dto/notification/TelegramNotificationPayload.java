package com.smmpanel.dto.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TelegramNotificationPayload {

    @JsonProperty("Order_id")
    private Long orderId;

    @JsonProperty("Amount")
    private String amount;

    @JsonProperty("Service")
    private String service;

    @JsonProperty("Status")
    private String status; // "completed" or "failed" (optional for new orders)

    @JsonProperty("Completed")
    private Integer completed; // Optional - only for completed/failed orders
}

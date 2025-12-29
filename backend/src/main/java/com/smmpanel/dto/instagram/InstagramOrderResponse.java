package com.smmpanel.dto.instagram;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO from Instagram bot after creating an order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstagramOrderResponse {

    /**
     * Whether the order was created successfully
     */
    private boolean success;

    /**
     * Bot's internal order ID
     */
    private String id;

    /**
     * Error message if creation failed
     */
    private String error;
}

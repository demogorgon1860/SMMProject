package com.smmpanel.service;

import com.smmpanel.entity.OrderStatus;
import lombok.Builder;
import lombok.Data;

/** State transition result */
@Builder
@Data
public class StateTransitionResult {
    private final Long orderId;
    private final boolean success;
    private final OrderStatus fromStatus;
    private final OrderStatus toStatus;
    private final String errorMessage;

    public static StateTransitionResult success(
            Long orderId, OrderStatus fromStatus, OrderStatus toStatus) {
        return StateTransitionResult.builder()
                .orderId(orderId)
                .success(true)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .build();
    }

    public static StateTransitionResult failed(
            Long orderId, OrderStatus fromStatus, OrderStatus toStatus, String errorMessage) {
        return StateTransitionResult.builder()
                .orderId(orderId)
                .success(false)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .errorMessage(errorMessage)
                .build();
    }
    
    // Helper method for tests
    public StateTransitionResult newStatus(OrderStatus status) {
        return StateTransitionResult.builder()
                .orderId(this.orderId)
                .success(this.success)
                .fromStatus(this.fromStatus)
                .toStatus(status)
                .errorMessage(this.errorMessage)
                .build();
    }
}
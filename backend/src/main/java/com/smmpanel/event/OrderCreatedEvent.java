package com.smmpanel.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * Event published when a new order is created
 */
@Getter
public class OrderCreatedEvent extends ApplicationEvent {
    private final Long orderId;
    private final Long userId;
    private final LocalDateTime createdAt;

    public OrderCreatedEvent(Object source, Long orderId, Long userId) {
        super(source);
        this.orderId = orderId;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
    }
} 
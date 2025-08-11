package com.smmpanel.event;

import java.time.LocalDateTime;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/** Event published when a new order is created */
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

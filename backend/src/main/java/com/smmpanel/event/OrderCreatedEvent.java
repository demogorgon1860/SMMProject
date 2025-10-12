package com.smmpanel.event;

import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Event published when a new order is created */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long orderId;
    private Long userId;
    private Long serviceId;
    private Integer quantity;
    private LocalDateTime timestamp;
    private LocalDateTime createdAt;

    // Constructor for compatibility with existing code
    public OrderCreatedEvent(Object source, Long orderId, Long userId) {
        this.orderId = orderId;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.timestamp = LocalDateTime.now();
    }
}

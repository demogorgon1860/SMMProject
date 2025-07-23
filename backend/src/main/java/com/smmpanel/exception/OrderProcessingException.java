package com.smmpanel.exception;

import com.smmpanel.entity.OrderStatus;
import lombok.Getter;

@Getter
public class OrderProcessingException extends RuntimeException {
    private final Long orderId;
    private final OrderStatus currentStatus;
    private final Boolean retryable;
    
    public OrderProcessingException(String message, Long orderId, OrderStatus currentStatus) {
        this(message, orderId, currentStatus, null, null);
    }
    
    public OrderProcessingException(String message, Long orderId, OrderStatus currentStatus, 
                                  Boolean retryable) {
        this(message, orderId, currentStatus, retryable, null);
    }
    
    public OrderProcessingException(String message, Long orderId, OrderStatus currentStatus,
                                  Boolean retryable, Throwable cause) {
        super(message, cause);
        this.orderId = orderId;
        this.currentStatus = currentStatus;
        this.retryable = retryable;
    }
}

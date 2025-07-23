package com.smmpanel.event;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderStatusChangedEvent extends ApplicationEvent {
    
    private final Order order;
    private final OrderStatus oldStatus;
    private final OrderStatus newStatus;
    
    public OrderStatusChangedEvent(Order order, OrderStatus oldStatus, OrderStatus newStatus) {
        super(order);
        this.order = order;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
}

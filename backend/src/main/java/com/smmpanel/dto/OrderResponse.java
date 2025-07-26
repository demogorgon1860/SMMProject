package com.smmpanel.dto;

import com.smmpanel.entity.OrderStatus;
import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class OrderResponse {
    private Long id;
    private Long userId;
    private Long serviceId;
    private String serviceName;
    private String link;
    private Integer quantity;
    private BigDecimal charge;
    private Integer startCount;
    private Integer remains;
    private OrderStatus status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
} 
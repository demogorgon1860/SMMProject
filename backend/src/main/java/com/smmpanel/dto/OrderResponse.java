package com.smmpanel.dto;

import com.smmpanel.entity.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
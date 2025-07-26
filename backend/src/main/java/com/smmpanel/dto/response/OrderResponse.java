package com.smmpanel.dto.response;

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
    private Integer service;
    private String status;
    private String link;
    private Integer quantity;
    private Integer startCount;
    private Integer remains;
    private String charge;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
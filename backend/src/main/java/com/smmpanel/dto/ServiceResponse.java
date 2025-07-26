package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class ServiceResponse {
    private Long id;
    private String name;
    private String category;
    private Integer minOrder;
    private Integer maxOrder;
    private BigDecimal pricePer1000;
    private String description;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
} 
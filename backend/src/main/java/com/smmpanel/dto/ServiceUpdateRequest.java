package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.math.BigDecimal;

@Data
@Builder
public class ServiceUpdateRequest {
    private String name;
    private String category;
    private Integer minOrder;
    private Integer maxOrder;
    private BigDecimal pricePer1000;
    private String description;
    private Boolean active;
} 
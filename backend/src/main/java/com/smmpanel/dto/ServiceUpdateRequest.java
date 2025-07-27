package com.smmpanel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceUpdateRequest {
    private String name;
    private String category;
    private Integer minOrder;
    private Integer maxOrder;
    private BigDecimal pricePer1000;
    private String description;
    private Boolean active;
} 
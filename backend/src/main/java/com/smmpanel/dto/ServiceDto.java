package com.smmpanel.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceDto {
    private Long id;
    private String name;
    private String category;
    private Integer minOrder;
    private Integer maxOrder;
    private String pricePer1000;
    private String description;
}

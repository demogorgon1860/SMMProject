package com.smmpanel.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    private Long id;
    private Integer service;
    private String link;
    private Integer quantity;
    
    @JsonProperty("start_count")
    private Integer startCount;
    
    private Integer remains;
    private String status;
    private String charge;
}
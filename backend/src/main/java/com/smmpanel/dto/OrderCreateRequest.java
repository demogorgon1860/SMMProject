package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

@Data
@Builder
public class OrderCreateRequest {
    @NotNull(message = "Service ID is required")
    private Long serviceId;
    @NotBlank(message = "Link is required")
    private String link;
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private Integer quantity;
} 
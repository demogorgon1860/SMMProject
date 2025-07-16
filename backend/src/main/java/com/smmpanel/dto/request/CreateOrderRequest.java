package com.smmpanel.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateOrderRequest {
    
    @NotNull(message = "Service ID is required")
    @Positive(message = "Service ID must be positive")
    private Integer service;
    
    @NotBlank(message = "Link is required")
    @Pattern(regexp = "^https?://(www\\.)?(youtube\\.com|youtu\\.be)/.+", 
             message = "Invalid YouTube URL")
    private String link;
    
    @NotNull(message = "Quantity is required")
    @Min(value = 100, message = "Minimum quantity is 100")
    @Max(value = 1000000, message = "Maximum quantity is 1,000,000")
    private Integer quantity;
}
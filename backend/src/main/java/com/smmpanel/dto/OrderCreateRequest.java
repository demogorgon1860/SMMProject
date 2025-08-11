package com.smmpanel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateRequest {
    @NotNull(message = "Service ID is required") @Positive(message = "Service ID must be positive") private Long serviceId;

    @NotBlank(message = "Link is required")
    @Pattern(regexp = "^https://youtube\\.com/watch\\?v=.*", message = "Invalid YouTube URL")
    private String link;

    @NotNull(message = "Quantity is required") @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 1000000, message = "Quantity cannot exceed 1,000,000")
    private Integer quantity;
}

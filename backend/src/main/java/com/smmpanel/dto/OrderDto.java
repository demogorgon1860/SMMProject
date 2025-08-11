package com.smmpanel.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDto {
    @NotNull(message = "Service ID is required") private Long serviceId;

    @NotBlank(message = "Link is required")
    @Pattern(
            regexp = "^(https?://)?([\\da-z.-]+)\\.([a-z.]{2,6})[/\\w .-]*/?$",
            message = "Link must be a valid URL")
    private String link;

    @NotNull(message = "Quantity is required") @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    private Long orderId;

    private String status;

    @Min(value = 0, message = "Start count cannot be negative")
    private Integer startCount;

    @Min(value = 0, message = "Remains cannot be negative")
    private Integer remains;

    @DecimalMin(value = "0.0", inclusive = false, message = "Total price must be greater than 0")
    private BigDecimal totalPrice;

    // Perfect Panel compatibility fields
    private String externalOrderId;

    private String metadata;
}

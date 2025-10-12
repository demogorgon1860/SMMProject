package com.smmpanel.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request DTO for Mass Order creation Accepts bulk text input with multiple orders */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MassOrderRequest {

    @NotBlank(message = "Orders text is required")
    @Size(max = 10000, message = "Orders text cannot exceed 10000 characters")
    private String ordersText;

    // Optional field to specify the delimiter (default is "|")
    @Builder.Default private String delimiter = "|";

    // Maximum number of orders allowed in a single request
    @Builder.Default private Integer maxOrders = 100;
}

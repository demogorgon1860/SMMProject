package com.smmpanel.dto.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderActionRequest {
    @NotBlank(message = "Action is required")
    private String action;

    private String reason;
    private Integer newStartCount;
    private Integer newQuantity;

    /** For partial action: admin-specified remaining quantity for refund calculation */
    @Min(value = 0, message = "Remains cannot be negative")
    private Integer remains;
}

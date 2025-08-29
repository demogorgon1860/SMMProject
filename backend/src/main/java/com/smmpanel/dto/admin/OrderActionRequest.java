package com.smmpanel.dto.admin;

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
}

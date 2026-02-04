package com.smmpanel.dto.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkActionRequest {
    @NotEmpty(message = "Order IDs are required")
    private List<Long> orderIds;

    @NotBlank(message = "Action is required")
    private String action;

    private String reason;

    /** For bulk partial action: admin-specified remaining quantity for refund calculation */
    @Min(value = 0, message = "Remains cannot be negative")
    private Integer remains;
}

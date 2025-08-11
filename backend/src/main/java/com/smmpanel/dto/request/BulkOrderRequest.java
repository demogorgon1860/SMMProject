package com.smmpanel.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkOrderRequest {
    @NotEmpty(message = "Order IDs cannot be empty")
    @Size(max = 100, message = "Cannot process more than 100 orders at once")
    private List<@Min(value = 1, message = "Order ID must be positive") Long> orderIds;

    @NotBlank(message = "Action is required")
    @Pattern(regexp = "CANCEL|PAUSE|RESUME|COMPLETE", message = "Invalid bulk action")
    private String action;

    private String reason;
}

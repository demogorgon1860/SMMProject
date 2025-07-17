package com.smmpanel.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

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
}

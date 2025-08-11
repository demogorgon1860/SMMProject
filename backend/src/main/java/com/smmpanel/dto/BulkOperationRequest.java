package com.smmpanel.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BulkOperationRequest {
    private String operation; // "CANCEL", "PAUSE", "RESUME", "REFUND"
    private List<Long> orderIds;
    private String reason;
}

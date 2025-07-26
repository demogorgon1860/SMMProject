package com.smmpanel.dto;

import lombok.Data;
import lombok.Builder;
import java.util.List;

@Data
@Builder
public class BulkOperationRequest {
    private String operation; // "CANCEL", "PAUSE", "RESUME", "REFUND"
    private List<Long> orderIds;
    private String reason;
} 
package com.smmpanel.dto.order;

import lombok.*;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkOperationResult {
    private int totalOrders;
    private int successfulOrders;
    private int failedOrders;
    private List<String> errors;
    private String operation;
} 
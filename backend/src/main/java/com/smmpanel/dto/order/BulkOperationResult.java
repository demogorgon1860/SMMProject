package com.smmpanel.dto.order;

import java.util.List;
import lombok.*;

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

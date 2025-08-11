package com.smmpanel.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkOperationResult {
    private boolean success;
    private String message;
    private int totalRequested;
    private int successful;
    private int failed;
    private List<String> errors;
}

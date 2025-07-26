package com.smmpanel.dto.response;

import lombok.Data;
import java.util.List;

@Data
public class BulkOperationResult {
    private int totalRequested;
    private int successful;
    private int failed;
    private List<String> errors;
} 
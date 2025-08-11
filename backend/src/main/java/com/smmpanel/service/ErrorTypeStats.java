package com.smmpanel.service;

import lombok.Builder;
import lombok.Data;

/** Error type statistics */
@Builder
@Data
public class ErrorTypeStats {
    private final String errorType;
    private final long count;
    private final double percentage;
}

package com.smmpanel.service;

import lombok.Builder;
import lombok.Data;

/** Error type statistics for DLQ */
@Builder
@Data
public class DlqErrorTypeStats {
    private final String errorType;
    private final Long count;
}

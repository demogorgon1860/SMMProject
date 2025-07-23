package com.smmpanel.service.monitoring;

public enum AlertLevel {
    INFO,       // For informational messages that don't require immediate action
    WARNING,    // For issues that should be addressed but aren't critical
    CRITICAL    // For critical issues that require immediate attention
}

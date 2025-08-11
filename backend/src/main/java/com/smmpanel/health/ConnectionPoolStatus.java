package com.smmpanel.health;

import lombok.Builder;
import lombok.Data;

/** Connection pool status information */
@Data
@Builder
public class ConnectionPoolStatus {
    private boolean healthy;
    private String status;
    private String reason;
    private String poolName;
    private int activeConnections;
    private int idleConnections;
    private int totalConnections;
    private int maxConnections;
    private int threadsAwaiting;
    private double usagePercentage;
    private long connectionTimeout;
    private long validationTimeout;
    private String warning;
}

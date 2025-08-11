package com.smmpanel.health;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Health service for HikariCP connection pool Monitors connection pool health and provides detailed
 * status information
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionPoolHealthIndicator {

    private final DataSource dataSource;

    /** Check connection pool health status */
    public ConnectionPoolStatus getHealth() {
        try {
            if (!(dataSource instanceof HikariDataSource)) {
                return ConnectionPoolStatus.builder()
                        .healthy(false)
                        .status("DOWN")
                        .reason("DataSource is not HikariCP")
                        .build();
            }

            HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

            // Check if connection pool is closed
            if (hikariDataSource.isClosed()) {
                return ConnectionPoolStatus.builder()
                        .healthy(false)
                        .status("DOWN")
                        .reason("Connection pool is closed")
                        .poolName(hikariDataSource.getPoolName())
                        .build();
            }

            HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

            if (poolMXBean == null) {
                return ConnectionPoolStatus.builder()
                        .healthy(false)
                        .status("DOWN")
                        .reason("Unable to access pool statistics")
                        .poolName(hikariDataSource.getPoolName())
                        .build();
            }

            // Collect pool metrics
            int activeConnections = poolMXBean.getActiveConnections();
            int idleConnections = poolMXBean.getIdleConnections();
            int totalConnections = poolMXBean.getTotalConnections();
            int threadsAwaiting = poolMXBean.getThreadsAwaitingConnection();
            long connectionTimeout = hikariDataSource.getConnectionTimeout();
            int maxPoolSize = hikariDataSource.getMaximumPoolSize();

            // Calculate usage percentage
            double usagePercentage =
                    totalConnections > 0 ? (double) activeConnections / totalConnections * 100 : 0;

            String warning = null;
            String status = "UP";

            // Check for warning conditions
            if (usagePercentage > 90) {
                warning = "Very high connection usage";
                status = "UP_WITH_WARNINGS";
            } else if (usagePercentage > 75) {
                warning = "High connection usage";
            }

            if (threadsAwaiting > 10) {
                warning =
                        warning != null
                                ? warning + "; Many threads waiting"
                                : "Many threads waiting for connections";
                status = "UP_WITH_WARNINGS";
            } else if (threadsAwaiting > 5) {
                warning =
                        warning != null
                                ? warning + "; Some threads waiting"
                                : "Some threads waiting for connections";
            }

            // Check for critical conditions that should mark as DOWN
            if (threadsAwaiting > 50) {
                return ConnectionPoolStatus.builder()
                        .healthy(false)
                        .status("DOWN")
                        .reason("Too many threads waiting for connections")
                        .poolName(hikariDataSource.getPoolName())
                        .activeConnections(activeConnections)
                        .idleConnections(idleConnections)
                        .totalConnections(totalConnections)
                        .maxConnections(maxPoolSize)
                        .threadsAwaiting(threadsAwaiting)
                        .usagePercentage(usagePercentage)
                        .connectionTimeout(connectionTimeout)
                        .validationTimeout(hikariDataSource.getValidationTimeout())
                        .build();
            }

            if (usagePercentage > 95 && threadsAwaiting > 0) {
                return ConnectionPoolStatus.builder()
                        .healthy(false)
                        .status("DOWN")
                        .reason("Connection pool exhausted")
                        .poolName(hikariDataSource.getPoolName())
                        .activeConnections(activeConnections)
                        .idleConnections(idleConnections)
                        .totalConnections(totalConnections)
                        .maxConnections(maxPoolSize)
                        .threadsAwaiting(threadsAwaiting)
                        .usagePercentage(usagePercentage)
                        .connectionTimeout(connectionTimeout)
                        .validationTimeout(hikariDataSource.getValidationTimeout())
                        .build();
            }

            return ConnectionPoolStatus.builder()
                    .healthy(true)
                    .status(status)
                    .poolName(hikariDataSource.getPoolName())
                    .activeConnections(activeConnections)
                    .idleConnections(idleConnections)
                    .totalConnections(totalConnections)
                    .maxConnections(maxPoolSize)
                    .threadsAwaiting(threadsAwaiting)
                    .usagePercentage(usagePercentage)
                    .connectionTimeout(connectionTimeout)
                    .validationTimeout(hikariDataSource.getValidationTimeout())
                    .warning(warning)
                    .build();

        } catch (Exception e) {
            log.error("Error checking connection pool health", e);
            return ConnectionPoolStatus.builder()
                    .healthy(false)
                    .status("DOWN")
                    .reason("Error checking connection pool: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get detailed connection pool status for administrative purposes
     *
     * @return detailed status string
     */
    public String getDetailedStatus() {
        ConnectionPoolStatus status = getHealth();

        if (status.getPoolName() == null) {
            return status.getReason();
        }

        return String.format(
                "Pool: %s | Status: %s | Usage: %.1f%% (%d/%d) | Idle: %d | Awaiting: %d | Timeout:"
                        + " %dms%s",
                status.getPoolName(),
                status.isHealthy() ? "HEALTHY" : "UNHEALTHY",
                status.getUsagePercentage(),
                status.getActiveConnections(),
                status.getTotalConnections(),
                status.getIdleConnections(),
                status.getThreadsAwaiting(),
                status.getConnectionTimeout(),
                status.getWarning() != null ? " | Warning: " + status.getWarning() : "");
    }
}

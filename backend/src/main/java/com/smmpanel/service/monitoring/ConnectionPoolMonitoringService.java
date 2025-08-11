package com.smmpanel.service.monitoring;

import com.smmpanel.service.AlertService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.Arrays;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionPoolMonitoringService {

    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;
    private final AlertService alertService;

    @Value("${app.monitoring.connection-pool.high-usage-threshold:75}")
    private double highUsageThreshold;

    @Value("${app.monitoring.connection-pool.critical-usage-threshold:90}")
    private double criticalUsageThreshold;

    @Value("${app.monitoring.connection-pool.max-awaiting-threads:10}")
    private int maxAwaitingThreads;

    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void monitorConnectionPool() {
        if (!(dataSource instanceof HikariDataSource)) {
            return;
        }

        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

        if (poolMXBean != null) {
            // Record core metrics
            recordPoolMetrics(poolMXBean);

            // Check for potential issues
            checkPoolHealth(poolMXBean);
        }
    }

    private void recordPoolMetrics(HikariPoolMXBean poolMXBean) {
        // Active connections
        meterRegistry.gauge(
                "hikaricp.connections.active",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getActiveConnections);

        // Idle connections
        meterRegistry.gauge(
                "hikaricp.connections.idle",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getIdleConnections);

        // Total connections
        meterRegistry.gauge(
                "hikaricp.connections.total",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getTotalConnections);

        // Threads awaiting connection
        meterRegistry.gauge(
                "hikaricp.connections.pending",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getThreadsAwaitingConnection);
    }

    private void checkPoolHealth(HikariPoolMXBean poolMXBean) {
        int activeConnections = poolMXBean.getActiveConnections();
        int totalConnections = poolMXBean.getTotalConnections();
        int threadsAwaiting = poolMXBean.getThreadsAwaitingConnection();

        // Calculate connection usage percentage
        double connectionUsage =
                totalConnections > 0 ? (double) activeConnections / totalConnections * 100 : 0;

        // Critical connection usage alert
        if (connectionUsage >= criticalUsageThreshold) {
            String message =
                    String.format(
                            "CRITICAL: Connection pool usage at %.2f%% (%d active / %d total)",
                            connectionUsage, activeConnections, totalConnections);
            log.error(message);

            try {
                alertService.sendAlert("Connection Pool Critical Usage", message);
            } catch (Exception e) {
                log.error("Failed to send critical usage alert", e);
            }
        }
        // High connection usage warning
        else if (connectionUsage >= highUsageThreshold) {
            log.warn(
                    "High connection pool usage: {}% ({} active / {} total)",
                    String.format("%.2f", connectionUsage), activeConnections, totalConnections);
        }

        // Critical threads awaiting alert
        if (threadsAwaiting >= maxAwaitingThreads) {
            String message =
                    String.format(
                            "CRITICAL: %d threads waiting for database connections (threshold: %d)",
                            threadsAwaiting, maxAwaitingThreads);
            log.error(message);

            try {
                alertService.sendAlert("Connection Pool Thread Congestion", message);
            } catch (Exception e) {
                log.error("Failed to send thread congestion alert", e);
            }
        }
        // Warning for threads awaiting
        else if (threadsAwaiting > 0) {
            log.warn("{} threads waiting for database connections", threadsAwaiting);
        }

        // Log detailed pool statistics at debug level
        log.debug(
                "Connection pool stats - Active: {}, Idle: {}, Total: {}, Waiting: {}, Usage:"
                        + " {:.2f}%",
                activeConnections,
                poolMXBean.getIdleConnections(),
                totalConnections,
                threadsAwaiting,
                connectionUsage);
    }

    /**
     * Get connection pool health status
     *
     * @return true if pool is healthy, false otherwise
     */
    public boolean isPoolHealthy() {
        if (!(dataSource instanceof HikariDataSource)) {
            return false;
        }

        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

        if (poolMXBean == null) {
            return false;
        }

        // Check if there are too many threads waiting
        if (poolMXBean.getThreadsAwaitingConnection() > 10) {
            return false;
        }

        // Check if connection usage is too high
        double connectionUsage =
                (double) poolMXBean.getActiveConnections() / poolMXBean.getTotalConnections() * 100;
        if (connectionUsage > 90) {
            return false;
        }

        return true;
    }

    /**
     * Get current connection pool statistics
     *
     * @return String containing pool statistics
     */
    public String getPoolStats() {
        if (!(dataSource instanceof HikariDataSource)) {
            return "Not a HikariCP datasource";
        }

        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        HikariPoolMXBean poolMXBean = hikariDataSource.getHikariPoolMXBean();

        if (poolMXBean == null) {
            return "Pool statistics not available";
        }

        return String.format(
                "Connection Pool Stats:%n"
                        + "Active Connections: %d%n"
                        + "Idle Connections: %d%n"
                        + "Total Connections: %d%n"
                        + "Threads Awaiting Connection: %d%n"
                        + "Connection Timeout: %dms%n"
                        + "Validation Timeout: %dms%n"
                        + "Max Pool Size: %d",
                poolMXBean.getActiveConnections(),
                poolMXBean.getIdleConnections(),
                poolMXBean.getTotalConnections(),
                poolMXBean.getThreadsAwaitingConnection(),
                hikariDataSource.getConnectionTimeout(),
                hikariDataSource.getValidationTimeout(),
                hikariDataSource.getMaximumPoolSize());
    }
}

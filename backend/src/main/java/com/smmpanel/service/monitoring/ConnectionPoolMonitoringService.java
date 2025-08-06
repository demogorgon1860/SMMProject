package com.smmpanel.service.monitoring;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionPoolMonitoringService {

    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;

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
        meterRegistry.gauge("hikaricp.connections.active",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getActiveConnections);

        // Idle connections
        meterRegistry.gauge("hikaricp.connections.idle",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getIdleConnections);

        // Total connections
        meterRegistry.gauge("hikaricp.connections.total",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getTotalConnections);

        // Threads awaiting connection
        meterRegistry.gauge("hikaricp.connections.pending",
                Arrays.asList(Tag.of("pool", "SMMPanelConnectionPool")),
                poolMXBean,
                HikariPoolMXBean::getThreadsAwaitingConnection);
    }

    private void checkPoolHealth(HikariPoolMXBean poolMXBean) {
        int activeConnections = poolMXBean.getActiveConnections();
        int totalConnections = poolMXBean.getTotalConnections();
        int threadsAwaiting = poolMXBean.getThreadsAwaitingConnection();
        
        // Calculate connection usage percentage
        double connectionUsage = (double) activeConnections / totalConnections * 100;

        // High connection usage warning
        if (connectionUsage > 75) {
            log.warn("High connection pool usage: {}% ({} active / {} total)",
                    String.format("%.2f", connectionUsage),
                    activeConnections,
                    totalConnections);
        }

        // Connection wait warning
        if (threadsAwaiting > 0) {
            log.warn("{} threads waiting for database connections",
                    threadsAwaiting);
        }

        // Log detailed pool statistics at debug level
        log.debug("Connection pool stats - Active: {}, Idle: {}, Total: {}, Waiting: {}",
                activeConnections,
                poolMXBean.getIdleConnections(),
                totalConnections,
                threadsAwaiting);
    }

    /**
     * Get connection pool health status
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
        double connectionUsage = (double) poolMXBean.getActiveConnections() / 
                               poolMXBean.getTotalConnections() * 100;
        if (connectionUsage > 90) {
            return false;
        }

        return true;
    }

    /**
     * Get current connection pool statistics
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
                "Connection Pool Stats:%n" +
                "Active Connections: %d%n" +
                "Idle Connections: %d%n" +
                "Total Connections: %d%n" +
                "Threads Awaiting Connection: %d%n" +
                "Connection Timeout: %dms%n" +
                "Validation Timeout: %dms%n" +
                "Max Pool Size: %d",
                poolMXBean.getActiveConnections(),
                poolMXBean.getIdleConnections(),
                poolMXBean.getTotalConnections(),
                poolMXBean.getThreadsAwaitingConnection(),
                hikariDataSource.getConnectionTimeout(),
                hikariDataSource.getValidationTimeout(),
                hikariDataSource.getMaximumPoolSize()
        );
    }
}

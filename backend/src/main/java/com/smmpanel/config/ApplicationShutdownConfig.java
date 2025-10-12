package com.smmpanel.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Application shutdown configuration to ensure proper cleanup of resources. This addresses the
 * HikariCP housekeeper thread not being properly shut down issue.
 */
@Slf4j
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApplicationShutdownConfig implements ApplicationListener<ContextClosedEvent> {

    @Autowired(required = false)
    private DataSource dataSource;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Application shutdown initiated - performing cleanup tasks");

        // Shutdown HikariCP properly
        shutdownHikariCP();

        // Give threads time to complete
        gracefulThreadShutdown();
    }

    private void shutdownHikariCP() {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            if (!hikariDataSource.isClosed()) {
                log.info(
                        "Shutting down HikariCP connection pool: {}",
                        hikariDataSource.getPoolName());

                try {
                    // Set a reasonable timeout for eviction
                    hikariDataSource.setIdleTimeout(TimeUnit.SECONDS.toMillis(1));
                    hikariDataSource.setMaxLifetime(TimeUnit.SECONDS.toMillis(2));

                    // Evict connections
                    var poolMXBean = hikariDataSource.getHikariPoolMXBean();
                    if (poolMXBean != null) {
                        poolMXBean.softEvictConnections();

                        // Wait a bit for eviction to complete
                        Thread.sleep(100);

                        log.info(
                                "Pool stats before shutdown - Active: {}, Idle: {}, Total: {}",
                                poolMXBean.getActiveConnections(),
                                poolMXBean.getIdleConnections(),
                                poolMXBean.getTotalConnections());
                    }

                    // Close the datasource
                    hikariDataSource.close();
                    log.info("HikariCP connection pool closed successfully");

                } catch (Exception e) {
                    log.error("Error during HikariCP shutdown", e);
                }
            }
        }
    }

    private void gracefulThreadShutdown() {
        try {
            // Create a scheduled executor to handle the delayed shutdown
            var executor = Executors.newSingleThreadScheduledExecutor();

            // Schedule a task to log if shutdown is taking too long
            executor.schedule(
                    () -> {
                        log.warn("Shutdown taking longer than expected, forcing termination");
                    },
                    5,
                    TimeUnit.SECONDS);

            // Give HikariCP housekeeper thread time to shut down
            Thread.sleep(500);

            executor.shutdownNow();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted during graceful shutdown", e);
        }
    }
}

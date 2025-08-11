package com.smmpanel.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Slf4j
@Configuration
@org.springframework.context.annotation.Profile("!test")
public class DatabaseConfig {

    private HikariDataSource hikariDataSource;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${spring.datasource.hikari.maximum-pool-size:50}")
    private int maximumPoolSize;

    @Value("${spring.datasource.hikari.minimum-idle:10}")
    private int minimumIdle;

    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long idleTimeout;

    @Value("${spring.datasource.hikari.connection-timeout:30000}")
    private long connectionTimeout;

    @Value("${spring.datasource.hikari.max-lifetime:1800000}")
    private long maxLifetime;

    @Bean
    @Primary
    public DataSource primaryDataSource(MeterRegistry meterRegistry) {
        HikariConfig config = new HikariConfig();

        // Basic connection settings
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUsername);
        config.setPassword(dbPassword);
        config.setPoolName("SMMPanelConnectionPool");

        // Pool size configuration
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);

        // Connection lifecycle timeouts
        config.setIdleTimeout(idleTimeout);
        config.setConnectionTimeout(connectionTimeout);
        config.setMaxLifetime(maxLifetime);
        config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(4));

        // Connection test settings
        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(TimeUnit.SECONDS.toMillis(5));

        // Statement cache settings
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useUnicode", "true");
        config.addDataSourceProperty("characterEncoding", "utf8");

        // Memory leak prevention settings
        config.setLeakDetectionThreshold(TimeUnit.MINUTES.toMillis(1));
        config.setInitializationFailTimeout(TimeUnit.SECONDS.toMillis(30));
        config.setIsolateInternalQueries(true);
        config.setAllowPoolSuspension(false);

        // Additional PostgreSQL optimizations
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("socketTimeout", "60");
        config.addDataSourceProperty("loginTimeout", "30");

        // Metrics integration
        config.setMetricRegistry(meterRegistry);
        config.setRegisterMbeans(true);

        // Store reference for proper shutdown
        this.hikariDataSource = new HikariDataSource(config);

        log.info("HikariCP connection pool initialized with {} max connections", maximumPoolSize);

        return this.hikariDataSource;
    }

    /**
     * Gracefully shutdown the HikariCP connection pool This prevents memory leaks and ensures all
     * connections are properly closed
     */
    @PreDestroy
    public void closeConnectionPool() {
        if (hikariDataSource != null && !hikariDataSource.isClosed()) {
            log.info("Shutting down HikariCP connection pool...");

            try {
                // Get final pool statistics before shutdown
                if (hikariDataSource.getHikariPoolMXBean() != null) {
                    var poolMXBean = hikariDataSource.getHikariPoolMXBean();
                    log.info(
                            "Final pool stats - Active: {}, Idle: {}, Total: {}, Awaiting: {}",
                            poolMXBean.getActiveConnections(),
                            poolMXBean.getIdleConnections(),
                            poolMXBean.getTotalConnections(),
                            poolMXBean.getThreadsAwaitingConnection());
                }

                // Close the connection pool gracefully
                hikariDataSource.close();
                log.info("HikariCP connection pool shutdown completed successfully");

            } catch (Exception e) {
                log.error("Error occurred during connection pool shutdown", e);
            }
        }
    }
}

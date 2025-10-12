package com.smmpanel.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@Slf4j
@Configuration
@Profile("!test")
public class HikariConnectionPoolConfig {

    private HikariDataSource hikariDataSource;
    private final Environment environment;

    public HikariConnectionPoolConfig(Environment environment) {
        this.environment = environment;
    }

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${spring.datasource.hikari.maximum-pool-size:20}")
    private int dbMaxConnections;

    @Value("${spring.datasource.hikari.max-lifetime:1800000}")
    private long dbConnectionLifetime;

    @Value("${spring.datasource.hikari.connection-timeout:20000}")
    private long connectionTimeout;

    @Value("${spring.datasource.hikari.idle-timeout:600000}")
    private long idleTimeout;

    @Bean
    @Primary
    public DataSource optimizedDataSource(MeterRegistry meterRegistry) {
        HikariConfig config = new HikariConfig();

        // Basic connection settings
        config.setJdbcUrl(dbUrl);
        config.setUsername(dbUsername);
        config.setPassword(dbPassword);
        config.setPoolName("OptimizedSMMPanelPool");

        // Dynamic pool sizing: maxPoolSize = min(CPU*2+1, DB_max - 2)
        int availableCpus = Runtime.getRuntime().availableProcessors();
        int cpuBasedPoolSize = (availableCpus * 2) + 1;
        int maxPoolSize = Math.min(cpuBasedPoolSize, dbMaxConnections - 2);

        // Ensure minimum pool size constraints
        maxPoolSize = Math.max(maxPoolSize, 5); // minimum 5 connections
        int minimumIdle = Math.max(2, maxPoolSize / 4); // 25% of max as minimum idle

        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minimumIdle);

        log.info(
                "Configuring HikariCP: CPUs={}, CPU-based pool size={}, DB max connections={}",
                availableCpus,
                cpuBasedPoolSize,
                dbMaxConnections);
        log.info(
                "Final pool configuration: maxPoolSize={}, minimumIdle={}",
                maxPoolSize,
                minimumIdle);

        // Connection timeout settings with proper relationships
        // maxLifetime should be 5-10% lower than DB connection lifetime
        long maxLifetime = (long) (dbConnectionLifetime * 0.95); // 5% lower

        // Use the configured values from environment
        config.setMaxLifetime(dbConnectionLifetime);
        config.setIdleTimeout(this.idleTimeout);
        config.setConnectionTimeout(this.connectionTimeout);
        config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(4));

        log.info(
                "Timeout configuration: maxLifetime={}ms, idleTimeout={}ms, margin={}ms",
                maxLifetime,
                idleTimeout,
                maxLifetime - idleTimeout);

        // Environment-specific leak detection
        boolean isProduction = isProductionEnvironment();
        if (isProduction) {
            // Disable leak detection in production for performance
            config.setLeakDetectionThreshold(0);
            log.info("Leak detection DISABLED for production environment");
        } else {
            // Enable leak detection in non-production environments
            config.setLeakDetectionThreshold(TimeUnit.MINUTES.toMillis(1));
            log.info("Leak detection ENABLED for non-production environment (60s threshold)");
        }

        // Connection test settings
        config.setConnectionTestQuery("SELECT 1");
        config.setValidationTimeout(TimeUnit.SECONDS.toMillis(5));

        // Statement cache optimization
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "300");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        // PostgreSQL-specific optimizations
        config.addDataSourceProperty("tcpKeepAlive", "true");
        config.addDataSourceProperty("socketTimeout", "60");
        config.addDataSourceProperty("loginTimeout", "30");
        config.addDataSourceProperty("ApplicationName", "SMM-Panel");

        // Performance settings
        config.setInitializationFailTimeout(TimeUnit.SECONDS.toMillis(30));
        config.setIsolateInternalQueries(true);
        config.setAllowPoolSuspension(false);
        config.setAutoCommit(false); // Better transaction control

        // Metrics and monitoring
        config.setMetricRegistry(meterRegistry);
        config.setRegisterMbeans(true);

        this.hikariDataSource = new HikariDataSource(config);
        return this.hikariDataSource;
    }

    /** Determines if the current environment is production */
    private boolean isProductionEnvironment() {
        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logPoolConfiguration() {
        if (hikariDataSource != null && hikariDataSource.getHikariPoolMXBean() != null) {
            var poolMXBean = hikariDataSource.getHikariPoolMXBean();
            log.info("=== HikariCP Pool Configuration Summary ===");
            log.info("Pool Name: {}", hikariDataSource.getPoolName());
            log.info("Maximum Pool Size: {}", hikariDataSource.getMaximumPoolSize());
            log.info("Minimum Idle: {}", hikariDataSource.getMinimumIdle());
            log.info(
                    "Max Lifetime: {}ms ({}min)",
                    hikariDataSource.getMaxLifetime(),
                    TimeUnit.MILLISECONDS.toMinutes(hikariDataSource.getMaxLifetime()));
            log.info(
                    "Idle Timeout: {}ms ({}min)",
                    hikariDataSource.getIdleTimeout(),
                    TimeUnit.MILLISECONDS.toMinutes(hikariDataSource.getIdleTimeout()));
            log.info("Connection Timeout: {}ms", hikariDataSource.getConnectionTimeout());
            log.info(
                    "Leak Detection: {}",
                    hikariDataSource.getLeakDetectionThreshold() > 0
                            ? hikariDataSource.getLeakDetectionThreshold() + "ms"
                            : "DISABLED");
            log.info(
                    "Environment: {}", isProductionEnvironment() ? "PRODUCTION" : "NON-PRODUCTION");
            log.info("=== End Pool Configuration ===");
        }
    }

    @PreDestroy
    public void closeConnectionPool() {
        if (hikariDataSource != null && !hikariDataSource.isClosed()) {
            log.info("Shutting down optimized HikariCP connection pool...");

            try {
                // Log final pool statistics if available
                if (hikariDataSource.getHikariPoolMXBean() != null) {
                    var poolMXBean = hikariDataSource.getHikariPoolMXBean();
                    log.info(
                            "Final pool stats - Active: {}, Idle: {}, Total: {}, Awaiting: {}",
                            poolMXBean.getActiveConnections(),
                            poolMXBean.getIdleConnections(),
                            poolMXBean.getTotalConnections(),
                            poolMXBean.getThreadsAwaitingConnection());

                    // Soft evict connections before shutdown
                    poolMXBean.softEvictConnections();
                }

                // Give connections time to finish their work
                Thread.sleep(100);

                // Close the datasource which will shutdown the pool
                hikariDataSource.close();
                log.info("Optimized HikariCP connection pool shutdown completed successfully");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during HikariCP shutdown", e);
            } catch (Exception e) {
                log.error("Error occurred during optimized connection pool shutdown", e);
            }
        }
    }
}

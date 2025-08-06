package com.smmpanel.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

@Configuration
public class DatabaseConfig {

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
        
        // Leak detection
        config.setLeakDetectionThreshold(TimeUnit.MINUTES.toMillis(1));
        
        // Metrics integration
        config.setMetricRegistry(meterRegistry);
        config.setRegisterMbeans(true);

        return new HikariDataSource(config);
    }
}

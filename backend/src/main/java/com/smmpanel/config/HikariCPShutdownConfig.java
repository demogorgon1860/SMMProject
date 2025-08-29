package com.smmpanel.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration to properly shutdown HikariCP connection pool Prevents thread leaks on context
 * shutdown
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class HikariCPShutdownConfig {

    private final DataSource dataSource;

    @PreDestroy
    public void closeDataSource() {
        if (dataSource instanceof HikariDataSource) {
            log.info("Shutting down HikariCP connection pool...");
            try {
                ((HikariDataSource) dataSource).close();
                log.info("HikariCP connection pool shutdown complete");
            } catch (Exception e) {
                log.error("Error shutting down HikariCP connection pool", e);
            }
        }
    }
}

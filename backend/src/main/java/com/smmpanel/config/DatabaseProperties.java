package com.smmpanel.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Database Configuration Properties */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.datasource")
public class DatabaseProperties {

    @NotBlank private String url = "jdbc:postgresql://localhost:5432/smm_panel";

    @NotBlank private String username = "postgres";

    private String password = "password";

    @NotBlank private String driverClassName = "org.postgresql.Driver";

    private Hikari hikari = new Hikari();

    @Data
    public static class Hikari {
        @Min(1)
        private int connectionTimeout = 20000;

        @Min(1)
        private int idleTimeout = 300000;

        @Min(1)
        private int maxLifetime = 1200000;

        @Min(1)
        private int maximumPoolSize = 50;

        @Min(1)
        private int minimumIdle = 10;

        private String poolName = "SmmPanelHikariCP";

        @Min(1)
        private int leakDetectionThreshold = 60000;
    }
}

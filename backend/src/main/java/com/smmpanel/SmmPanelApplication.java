package com.smmpanel;

import com.smmpanel.config.AppProperties;
import com.smmpanel.config.JwtConfig;
import com.smmpanel.config.KafkaProperties;
import com.smmpanel.config.monitoring.SlaMonitoringProperties;
import com.smmpanel.config.order.OrderProcessingProperties;
import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        exclude = {
            org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
                    .class,
            org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration.class,
            org.springframework.boot.actuate.autoconfigure.metrics.export.otlp
                    .OtlpMetricsExportAutoConfiguration.class
        })
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableKafka
@EnableConfigurationProperties({
    AppProperties.class,
    OrderProcessingProperties.class,
    SlaMonitoringProperties.class,
    JwtConfig.class,
    KafkaProperties.class
})
public class SmmPanelApplication {

    static {
        // Load .env file before Spring Boot starts
        loadEnvironmentVariables();
    }

    public static void main(String[] args) {
        SpringApplication.run(SmmPanelApplication.class, args);
    }

    private static void loadEnvironmentVariables() {
        try {
            Path currentPath = Paths.get(System.getProperty("user.dir"));
            Path envPath = currentPath.resolve(".env");

            // If running from backend directory, check parent
            if (!Files.exists(envPath) && currentPath.getFileName().toString().equals("backend")) {
                envPath = currentPath.getParent().resolve(".env");
            }

            if (Files.exists(envPath)) {
                System.out.println("Loading environment variables from: " + envPath);

                Dotenv dotenv =
                        Dotenv.configure()
                                .directory(envPath.getParent().toString())
                                .ignoreIfMissing()
                                .systemProperties()
                                .load();

                // Set all as system properties for Spring to use
                dotenv.entries()
                        .forEach(
                                entry -> {
                                    System.setProperty(entry.getKey(), entry.getValue());
                                });

                System.out.println(
                        "Loaded " + dotenv.entries().size() + " environment variables from .env");
            } else {
                System.out.println(
                        "WARNING: .env file not found, using system environment variables");
            }
        } catch (Exception e) {
            System.err.println("Error loading .env file: " + e.getMessage());
        }
    }

    // taskExecutor is configured in AsyncConfig.java
}

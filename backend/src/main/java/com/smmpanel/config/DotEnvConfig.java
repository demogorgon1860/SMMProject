package com.smmpanel.config;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Configuration class to load environment variables from .env file This runs before Spring Boot
 * application starts to ensure all environment variables are available for configuration
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DotEnvConfig {

    private static final Logger logger = LoggerFactory.getLogger(DotEnvConfig.class);

    private static final Set<String> REQUIRED_VARIABLES = Set.of("DB_PASSWORD", "JWT_SECRET");

    private static final Set<String> OPTIONAL_VARIABLES =
            Set.of(
                    "REDIS_PASSWORD",
                    "BINOM_API_KEY",
                    "YOUTUBE_API_KEY",
                    "CRYPTOMUS_API_KEY",
                    "CRYPTOMUS_API_SECRET",
                    "CRYPTOMUS_MERCHANT_ID");

    static {
        // Load .env file before Spring Boot starts
        loadDotEnv();
    }

    private static void loadDotEnv() {
        try {
            // Try to find .env file in different locations
            Path projectRoot = findProjectRoot();
            Path envFile = projectRoot.resolve(".env");

            if (!Files.exists(envFile)) {
                logger.warn(".env file not found at: {}", envFile);
                logger.warn("Application will use system environment variables only");
                return;
            }

            logger.info("Loading environment variables from: {}", envFile);

            // Load .env file
            Dotenv dotenv =
                    Dotenv.configure()
                            .directory(projectRoot.toString())
                            .ignoreIfMissing()
                            .systemProperties() // Also set as system properties
                            .load();

            // Set all variables as system properties for Spring to pick up
            dotenv.entries()
                    .forEach(
                            entry -> {
                                String key = entry.getKey();
                                String value = entry.getValue();

                                // Set as system property
                                System.setProperty(key, value);

                                // Also set as environment variable (for child processes)
                                try {
                                    ProcessBuilder pb = new ProcessBuilder();
                                    pb.environment().put(key, value);
                                } catch (Exception e) {
                                    // Some environments don't allow setting env vars
                                    logger.debug(
                                            "Could not set environment variable {}: {}",
                                            key,
                                            e.getMessage());
                                }

                                // Log loaded variables (mask sensitive values)
                                if (key.contains("PASSWORD")
                                        || key.contains("SECRET")
                                        || key.contains("KEY")) {
                                    logger.debug("Loaded sensitive variable: {} = [MASKED]", key);
                                } else {
                                    logger.debug("Loaded variable: {} = {}", key, value);
                                }
                            });

            logger.info(
                    "Successfully loaded {} environment variables from .env file",
                    dotenv.entries().size());

        } catch (Exception e) {
            logger.error("Error loading .env file: {}", e.getMessage(), e);
        }
    }

    private static Path findProjectRoot() {
        // Try to find project root by looking for .env file
        Path currentPath = Paths.get(System.getProperty("user.dir"));

        // Check current directory first
        if (Files.exists(currentPath.resolve(".env"))) {
            return currentPath;
        }

        // Check parent directory (in case running from backend folder)
        Path parentPath = currentPath.getParent();
        if (parentPath != null && Files.exists(parentPath.resolve(".env"))) {
            return parentPath;
        }

        // Check if we're in backend folder and go up
        if (currentPath.getFileName().toString().equals("backend")) {
            parentPath = currentPath.getParent();
            if (parentPath != null && Files.exists(parentPath.resolve(".env"))) {
                return parentPath;
            }
        }

        // Default to current directory
        return currentPath;
    }

    @PostConstruct
    public void validateEnvironmentVariables() {
        logger.info("Validating required environment variables...");

        boolean hasErrors = false;

        // Check required variables
        for (String variable : REQUIRED_VARIABLES) {
            String value = System.getProperty(variable);
            if (value == null || value.isEmpty()) {
                value = System.getenv(variable);
            }

            if (value == null || value.isEmpty()) {
                logger.error("REQUIRED environment variable is missing: {}", variable);
                hasErrors = true;
            } else {
                logger.debug("[OK] Required variable present: {}", variable);
            }
        }

        // Check optional variables
        for (String variable : OPTIONAL_VARIABLES) {
            String value = System.getProperty(variable);
            if (value == null || value.isEmpty()) {
                value = System.getenv(variable);
            }

            if (value == null || value.isEmpty() || value.startsWith("placeholder")) {
                logger.warn(
                        "Optional environment variable is missing or using placeholder: {}",
                        variable);
            } else {
                logger.debug("[OK] Optional variable present: {}", variable);
            }
        }

        if (hasErrors) {
            logger.error("================================");
            logger.error("CRITICAL: Required environment variables are missing!");
            logger.error("Please ensure .env file exists and contains all required variables.");
            logger.error("Copy .env.example to .env and fill in the values.");
            logger.error("================================");
            // Don't fail startup, but warn strongly
        } else {
            logger.info("[SUCCESS] All required environment variables are present");
        }
    }
}

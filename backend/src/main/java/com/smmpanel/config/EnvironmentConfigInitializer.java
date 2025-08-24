package com.smmpanel.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Environment post processor that loads .env file before Spring Boot configuration This ensures
 * environment variables are available for @Value and ${} placeholders
 */
public class EnvironmentConfigInitializer implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        // Load .env file
        Path projectRoot = findProjectRoot();
        Path envFile = projectRoot.resolve(".env");

        if (!Files.exists(envFile)) {
            System.out.println("WARNING: .env file not found at: " + envFile);
            return;
        }

        System.out.println("Loading .env file from: " + envFile);

        try {
            Dotenv dotenv =
                    Dotenv.configure().directory(projectRoot.toString()).ignoreIfMissing().load();

            // Convert to property source
            Map<String, Object> envMap = new HashMap<>();
            dotenv.entries()
                    .forEach(
                            entry -> {
                                envMap.put(entry.getKey(), entry.getValue());
                                // Also set as system property for backward compatibility
                                System.setProperty(entry.getKey(), entry.getValue());
                            });

            // Add as highest priority property source
            MutablePropertySources propertySources = environment.getPropertySources();
            propertySources.addFirst(new MapPropertySource("dotenv", envMap));

            System.out.println(
                    "Successfully loaded " + envMap.size() + " environment variables from .env");

        } catch (Exception e) {
            System.err.println("Error loading .env file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Path findProjectRoot() {
        Path currentPath = Paths.get(System.getProperty("user.dir"));

        // Check current directory
        if (Files.exists(currentPath.resolve(".env"))) {
            return currentPath;
        }

        // Check parent directory
        Path parentPath = currentPath.getParent();
        if (parentPath != null && Files.exists(parentPath.resolve(".env"))) {
            return parentPath;
        }

        // If in backend folder, go up
        if (currentPath.getFileName().toString().equals("backend")) {
            parentPath = currentPath.getParent();
            if (parentPath != null) {
                return parentPath;
            }
        }

        return currentPath;
    }
}

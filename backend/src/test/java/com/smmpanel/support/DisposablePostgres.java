package com.smmpanel.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL for integration tests that depend on PostgreSQL semantics (row locks, DDL,
 * partitions): Testcontainers when Docker is available, otherwise a database given by {@code
 * DISPOSABLE_TEST_POSTGRES_URL} (plus {@code DISPOSABLE_TEST_POSTGRES_USER}/{@code
 * DISPOSABLE_TEST_POSTGRES_PASSWORD}) — WARNING: point that at a throwaway database; tests drop and
 * recreate schema objects in it. Tests using it should be {@code @EnabledIf} on {@link
 * #isAvailable()} so they are skipped where neither exists.
 */
public final class DisposablePostgres {

    private static final String EXTERNAL_URL = System.getenv("DISPOSABLE_TEST_POSTGRES_URL");

    private static PostgreSQLContainer<?> container;

    private DisposablePostgres() {}

    public static boolean isAvailable() {
        if (EXTERNAL_URL != null) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String jdbcUrl() {
        return EXTERNAL_URL != null ? EXTERNAL_URL : startedContainer().getJdbcUrl();
    }

    public static String username() {
        return EXTERNAL_URL != null
                ? envOrDefault("DISPOSABLE_TEST_POSTGRES_USER", "test")
                : startedContainer().getUsername();
    }

    public static String password() {
        return EXTERNAL_URL != null
                ? envOrDefault("DISPOSABLE_TEST_POSTGRES_PASSWORD", "test")
                : startedContainer().getPassword();
    }

    public static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }

    /**
     * Points the Spring datasource at this database, with the pool configured like prod's {@code
     * HikariConnectionPoolConfig}: non-autocommit connections (application.yml sets Hibernate's
     * {@code provider_disables_autocommit}, which requires them — and it means any write issued
     * outside a transaction is rolled back when the connection returns to the pool).
     */
    public static void registerDataSource(DynamicPropertyRegistry registry, String jdbcUrl) {
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", DisposablePostgres::username);
        registry.add("spring.datasource.password", DisposablePostgres::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.hikari.auto-commit", () -> "false");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("spring.liquibase.enabled", () -> "false");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    /** Started once per JVM; Testcontainers' reaper removes it when the JVM exits. */
    private static synchronized PostgreSQLContainer<?> startedContainer() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:15-alpine");
            container.start();
        }
        return container;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value != null ? value : fallback;
    }
}

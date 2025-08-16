package com.smmpanel.config;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class HikariConnectionPoolConfigTest {

    @Test
    void testDynamicPoolSizingBasedOnCPUs() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:h2:mem:testdb");
        environment.setProperty("spring.datasource.username", "sa");
        environment.setProperty("spring.datasource.password", "");
        environment.setProperty("app.database.max-connections", "100");
        environment.setProperty("app.database.connection-lifetime-ms", "1800000");

        HikariConnectionPoolConfig config = new HikariConnectionPoolConfig(environment);

        // Use reflection to set values for testing
        setField(config, "dbUrl", "jdbc:h2:mem:testdb");
        setField(config, "dbUsername", "sa");
        setField(config, "dbPassword", "");
        setField(config, "dbMaxConnections", 100);
        setField(config, "dbConnectionLifetime", 1800000L);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DataSource dataSource = config.optimizedDataSource(meterRegistry);

        assertTrue(dataSource instanceof HikariDataSource);
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        int availableCpus = Runtime.getRuntime().availableProcessors();
        int expectedMaxPoolSize = Math.min((availableCpus * 2) + 1, 100 - 2);
        expectedMaxPoolSize = Math.max(expectedMaxPoolSize, 5);

        assertEquals(expectedMaxPoolSize, hikariDataSource.getMaximumPoolSize());
        assertEquals(Math.max(2, expectedMaxPoolSize / 4), hikariDataSource.getMinimumIdle());

        // Cleanup
        hikariDataSource.close();
    }

    @Test
    void testTimeoutRelationships() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("app.database.connection-lifetime-ms", "1800000");

        HikariConnectionPoolConfig config = new HikariConnectionPoolConfig(environment);
        setField(config, "dbUrl", "jdbc:h2:mem:testdb");
        setField(config, "dbUsername", "sa");
        setField(config, "dbPassword", "");
        setField(config, "dbMaxConnections", 100);
        setField(config, "dbConnectionLifetime", 1800000L);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DataSource dataSource = config.optimizedDataSource(meterRegistry);
        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;

        long maxLifetime = hikariDataSource.getMaxLifetime();
        long idleTimeout = hikariDataSource.getIdleTimeout();

        // maxLifetime should be 5% lower than DB connection lifetime
        assertEquals((long) (1800000 * 0.95), maxLifetime);

        // idleTimeout should be < maxLifetime by at least 30s
        assertTrue(idleTimeout < maxLifetime);
        assertTrue(maxLifetime - idleTimeout >= TimeUnit.SECONDS.toMillis(60));

        // idleTimeout should be at least 5 minutes
        assertTrue(idleTimeout >= TimeUnit.MINUTES.toMillis(5));

        // Cleanup
        hikariDataSource.close();
    }

    @Test
    void testProductionEnvironmentDetection() {
        MockEnvironment prodEnvironment = new MockEnvironment();
        prodEnvironment.setActiveProfiles("prod");

        HikariConnectionPoolConfig prodConfig = new HikariConnectionPoolConfig(prodEnvironment);
        assertTrue(invokePrivateMethod(prodConfig, "isProductionEnvironment", Boolean.class));

        MockEnvironment devEnvironment = new MockEnvironment();
        devEnvironment.setActiveProfiles("dev");

        HikariConnectionPoolConfig devConfig = new HikariConnectionPoolConfig(devEnvironment);
        assertFalse(invokePrivateMethod(devConfig, "isProductionEnvironment", Boolean.class));
    }

    @Test
    void testLeakDetectionConfiguration() {
        // Test production environment (leak detection disabled)
        MockEnvironment prodEnvironment = new MockEnvironment();
        prodEnvironment.setActiveProfiles("production");
        prodEnvironment.setProperty("spring.datasource.url", "jdbc:h2:mem:testdb");
        prodEnvironment.setProperty("spring.datasource.username", "sa");
        prodEnvironment.setProperty("spring.datasource.password", "");

        HikariConnectionPoolConfig prodConfig = new HikariConnectionPoolConfig(prodEnvironment);
        setField(prodConfig, "dbUrl", "jdbc:h2:mem:testdb");
        setField(prodConfig, "dbUsername", "sa");
        setField(prodConfig, "dbPassword", "");
        setField(prodConfig, "dbMaxConnections", 100);
        setField(prodConfig, "dbConnectionLifetime", 1800000L);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DataSource prodDataSource = prodConfig.optimizedDataSource(meterRegistry);
        HikariDataSource prodHikariDS = (HikariDataSource) prodDataSource;

        assertEquals(0, prodHikariDS.getLeakDetectionThreshold());
        prodHikariDS.close();

        // Test non-production environment (leak detection enabled)
        MockEnvironment devEnvironment = new MockEnvironment();
        devEnvironment.setActiveProfiles("dev");
        devEnvironment.setProperty("spring.datasource.url", "jdbc:h2:mem:testdb");
        devEnvironment.setProperty("spring.datasource.username", "sa");
        devEnvironment.setProperty("spring.datasource.password", "");

        HikariConnectionPoolConfig devConfig = new HikariConnectionPoolConfig(devEnvironment);
        setField(devConfig, "dbUrl", "jdbc:h2:mem:testdb");
        setField(devConfig, "dbUsername", "sa");
        setField(devConfig, "dbPassword", "");
        setField(devConfig, "dbMaxConnections", 100);
        setField(devConfig, "dbConnectionLifetime", 1800000L);

        DataSource devDataSource = devConfig.optimizedDataSource(meterRegistry);
        HikariDataSource devHikariDS = (HikariDataSource) devDataSource;

        assertEquals(TimeUnit.MINUTES.toMillis(1), devHikariDS.getLeakDetectionThreshold());
        devHikariDS.close();
    }

    // Helper methods for testing
    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invokePrivateMethod(Object target, String methodName, Class<T> returnType) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return (T) method.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

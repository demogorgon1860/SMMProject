package com.smmpanel;

import static org.junit.jupiter.api.Assertions.*;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;

@Slf4j
public class ConnectivityVerificationTest {

    @Test
    public void testDirectRedisConnection() {
        log.info("Testing direct Redis connection...");

        RedisClient redisClient = RedisClient.create("redis://localhost:6379");
        try (StatefulRedisConnection<String, String> connection = redisClient.connect()) {
            RedisCommands<String, String> syncCommands = connection.sync();

            String pingResult = syncCommands.ping();
            assertEquals("PONG", pingResult);
            log.info("✓ Redis connection successful: {}", pingResult);

            syncCommands.set("test:key", "test-value");
            String value = syncCommands.get("test:key");
            assertEquals("test-value", value);
            log.info("✓ Redis read/write successful");

            syncCommands.del("test:key");
            log.info("✓ Redis operations completed successfully");
        } catch (Exception e) {
            log.error("✗ Redis connection failed: {}", e.getMessage());
            fail("Redis connection failed: " + e.getMessage());
        } finally {
            redisClient.shutdown();
        }
    }

    @Test
    public void testDirectKafkaConnection() {
        log.info("Testing direct Kafka connection...");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");

        try (AdminClient adminClient = AdminClient.create(props)) {
            var clusterResult = adminClient.describeCluster();
            var clusterId = clusterResult.clusterId().get(5, TimeUnit.SECONDS);
            assertNotNull(clusterId);
            log.info("✓ Kafka connection successful. Cluster ID: {}", clusterId);

            var nodes = clusterResult.nodes().get(5, TimeUnit.SECONDS);
            assertFalse(nodes.isEmpty());
            log.info("✓ Kafka brokers found: {}", nodes.size());

            var topics = adminClient.listTopics().names().get(5, TimeUnit.SECONDS);
            assertNotNull(topics);
            log.info("✓ Kafka topics accessible. Topic count: {}", topics.size());

            log.info("✓ Kafka operations completed successfully");
        } catch (Exception e) {
            log.error("✗ Kafka connection failed: {}", e.getMessage());
            fail("Kafka connection failed: " + e.getMessage());
        }
    }

    @Test
    public void testPostgreSQLConnection() {
        log.info("Testing PostgreSQL connection...");

        try {
            Class.forName("org.postgresql.Driver");

            String url = "jdbc:postgresql://localhost:5432/smm_panel";
            String user = "smm_admin";
            String password = System.getenv("DB_PASSWORD");

            if (password == null || password.isEmpty()) {
                password = "adminpassword123"; // Fallback for testing
            }

            try (var connection = java.sql.DriverManager.getConnection(url, user, password)) {
                assertNotNull(connection);
                assertFalse(connection.isClosed());
                log.info("✓ PostgreSQL connection successful");

                try (var stmt = connection.createStatement();
                        var rs = stmt.executeQuery("SELECT version()")) {
                    if (rs.next()) {
                        log.info("✓ PostgreSQL version: {}", rs.getString(1));
                    }
                }

                log.info("✓ PostgreSQL operations completed successfully");
            }
        } catch (Exception e) {
            log.error("✗ PostgreSQL connection failed: {}", e.getMessage());
            fail("PostgreSQL connection failed: " + e.getMessage());
        }
    }
}

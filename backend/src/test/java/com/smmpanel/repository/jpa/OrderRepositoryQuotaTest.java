package com.smmpanel.repository.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.entity.Service;
import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tests for {@link OrderRepository#sumConsumedQuantityByServiceAndLink} — the per-URL quota
 * aggregate used to block orders that would exceed the bot's profile-pool capacity for a single
 * Instagram URL. Covers the SUM(CASE WHEN ...) branching, status filter, time-window cutoff, and
 * per-(service, link) isolation.
 *
 * <p>Uses {@link DataJpaTest} (JPA slice) against a real PostgreSQL container — H2 cannot
 * reproduce the schema because entities use PG-specific features ({@code JSONB}, custom enum
 * types via {@code PostgreSQLEnumType}). When Docker is unavailable the entire class is
 * skipped via JUnit assumptions instead of failing — this keeps the suite green on dev
 * machines without Docker while still running in CI / on the production server.
 *
 * <p>The advisory-lock method ({@link OrderRepository#acquireQuotaLock}) is also PG-native and
 * exercised implicitly here via the same container.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@EnabledIf(
        value = "dockerAvailable",
        disabledReason =
                "Docker is not available. The test requires PostgreSQL via Testcontainers; "
                        + "H2 cannot reproduce the schema (uses JSONB and PG enum types).")
class OrderRepositoryQuotaTest {

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("smm_panel_quota_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add(
                "spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.liquibase.enabled", () -> "false");
        registry.add("spring.flyway.enabled", () -> "false");
        // Disable Hibernate L2 cache — main app uses Ehcache via JCache which fails to load
        // ehcache-config.xml from the classpath in test context.
        registry.add("spring.jpa.properties.hibernate.cache.use_second_level_cache", () -> "false");
        registry.add("spring.jpa.properties.hibernate.cache.use_query_cache", () -> "false");
        registry.add(
                "spring.jpa.properties.hibernate.cache.region.factory_class",
                () -> "org.hibernate.cache.internal.NoCachingRegionFactory");
    }

    private static final List<OrderStatus> COUNTING_STATUSES =
            List.of(
                    OrderStatus.PENDING,
                    OrderStatus.IN_PROGRESS,
                    OrderStatus.PROCESSING,
                    OrderStatus.ACTIVE,
                    OrderStatus.PARTIAL,
                    OrderStatus.COMPLETED,
                    OrderStatus.PAUSED,
                    OrderStatus.HOLDING);

    private static final String LINK_A = "https://instagram.com/p/AAA/";
    private static final String LINK_B = "https://instagram.com/p/BBB/";

    @Autowired private OrderRepository orderRepository;
    @Autowired private TestEntityManager entityManager;

    private User user;
    private Service serviceLikes;
    private Service serviceFollows;

    @BeforeEach
    void setUp() {
        user =
                entityManager.persistAndFlush(
                        User.builder()
                                .username("quota-tester-" + System.nanoTime())
                                .email("quota-" + System.nanoTime() + "@test.local")
                                .passwordHash("x")
                                .balance(BigDecimal.valueOf(10000))
                                .role(UserRole.USER)
                                .isActive(true)
                                .emailVerified(true)
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build());

        serviceLikes =
                entityManager.persistAndFlush(
                        Service.builder()
                                .name("Instagram Likes")
                                .category("Instagram")
                                .minOrder(10)
                                .maxOrder(275)
                                .pricePer1000(BigDecimal.valueOf(1.0))
                                .active(true)
                                .geoTargeting("US")
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build());

        serviceFollows =
                entityManager.persistAndFlush(
                        Service.builder()
                                .name("Instagram Followers")
                                .category("Instagram")
                                .minOrder(10)
                                .maxOrder(500)
                                .pricePer1000(BigDecimal.valueOf(2.0))
                                .active(true)
                                .geoTargeting("US")
                                .createdAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .build());
    }

    @Test
    @DisplayName("Returns 0 when no matching orders exist")
    void sum_returnsZero_whenNoOrders() {
        assertEquals(0L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("Active orders count by full quantity (slot reserved)")
    void sum_aggregatesActiveOrders_byQuantity() {
        save(order(serviceLikes, LINK_A, 50, 50, OrderStatus.PENDING, LocalDateTime.now()));
        save(order(serviceLikes, LINK_A, 30, 30, OrderStatus.IN_PROGRESS, LocalDateTime.now()));
        save(order(serviceLikes, LINK_A, 20, 20, OrderStatus.ACTIVE, LocalDateTime.now()));

        assertEquals(100L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("COMPLETED orders count by quantity (remains=0)")
    void sum_aggregatesCompletedOrders_byDelivered() {
        save(order(serviceLikes, LINK_A, 100, 0, OrderStatus.COMPLETED, LocalDateTime.now()));

        assertEquals(100L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("PARTIAL orders count by quantity - remains (only what was actually delivered)")
    void sum_aggregatesPartialOrders_byQuantityMinusRemains() {
        // PARTIAL with quantity=100, remains=20 → 80 delivered
        save(order(serviceLikes, LINK_A, 100, 20, OrderStatus.PARTIAL, LocalDateTime.now()));

        assertEquals(80L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("Terminal-status orders are excluded by the statuses filter")
    void sum_excludesCancelledFailedErrorRefillSuspended() {
        save(order(serviceLikes, LINK_A, 200, 200, OrderStatus.CANCELLED, LocalDateTime.now()));
        save(order(serviceLikes, LINK_A, 150, 150, OrderStatus.ERROR, LocalDateTime.now()));
        save(order(serviceLikes, LINK_A, 100, 100, OrderStatus.REFILL, LocalDateTime.now()));
        save(order(serviceLikes, LINK_A, 75, 75, OrderStatus.SUSPENDED, LocalDateTime.now()));

        assertEquals(0L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("Orders older than the cutoff are excluded")
    void sum_excludesOrdersBeforeCutoff() {
        // 50-day-old order — outside the 30-day window
        save(
                order(
                        serviceLikes,
                        LINK_A,
                        100,
                        0,
                        OrderStatus.COMPLETED,
                        LocalDateTime.now().minusDays(50)));
        // Recent order — inside the window
        save(
                order(
                        serviceLikes,
                        LINK_A,
                        40,
                        40,
                        OrderStatus.PENDING,
                        LocalDateTime.now().minusDays(5)));

        assertEquals(40L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("Other services on the same URL do not contribute to the sum")
    void sum_isPerServiceId() {
        save(order(serviceLikes, LINK_A, 100, 100, OrderStatus.PENDING, LocalDateTime.now()));
        save(order(serviceFollows, LINK_A, 200, 200, OrderStatus.PENDING, LocalDateTime.now()));

        assertEquals(100L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("Other URLs on the same service do not contribute to the sum")
    void sum_isPerLink() {
        save(order(serviceLikes, LINK_A, 100, 100, OrderStatus.PENDING, LocalDateTime.now()));
        save(order(serviceLikes, LINK_B, 250, 250, OrderStatus.PENDING, LocalDateTime.now()));

        assertEquals(100L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("Mixed scenario: active + partial + completed + cancelled all considered")
    void sum_combinesActiveDeliveredAndExcludesTerminal() {
        save(order(serviceLikes, LINK_A, 50, 50, OrderStatus.PENDING, LocalDateTime.now())); // 50
        save(order(serviceLikes, LINK_A, 80, 0, OrderStatus.COMPLETED, LocalDateTime.now())); // 80
        save(order(serviceLikes, LINK_A, 100, 40, OrderStatus.PARTIAL, LocalDateTime.now())); // 60
        save(order(serviceLikes, LINK_A, 200, 200, OrderStatus.CANCELLED, LocalDateTime.now())); // 0

        assertEquals(190L, sumNow(serviceLikes, LINK_A));
    }

    @Test
    @DisplayName("Advisory lock acquires successfully against real PostgreSQL")
    void acquireQuotaLock_succeeds() {
        // Smoke test — the lock is released automatically on transaction end. No assertion on
        // value because pg_advisory_xact_lock returns void; we only care that no exception is
        // thrown and the JPQL/native binding wiring works end-to-end against PG.
        orderRepository.acquireQuotaLock(serviceLikes.getId(), LINK_A);
    }

    private long sumNow(Service service, String link) {
        return orderRepository.sumConsumedQuantityByServiceAndLink(
                service.getId(), link, COUNTING_STATUSES, LocalDateTime.now().minusDays(30));
    }

    private Order order(
            Service service,
            String link,
            int quantity,
            int remains,
            OrderStatus status,
            LocalDateTime createdAt) {
        return Order.builder()
                .user(user)
                .service(service)
                .link(link)
                .quantity(quantity)
                .remains(remains)
                .charge(BigDecimal.valueOf(1.0))
                .startCount(0)
                .status(status)
                .processingPriority(0)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private void save(Order o) {
        entityManager.persistAndFlush(o);
    }
}

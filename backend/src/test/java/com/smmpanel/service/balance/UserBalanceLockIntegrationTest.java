package com.smmpanel.service.balance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smmpanel.entity.User;
import com.smmpanel.entity.UserRole;
import com.smmpanel.exception.InsufficientBalanceException;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.support.DisposablePostgres;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reproduces, against a real PostgreSQL, the concurrent same-user order interleaving that rejected
 * reseller orders in production with {@code StaleObjectStateException [User#856]}, and pins the
 * fix.
 *
 * <p>Production shape: {@code OrderService.createOrder} loads the {@link User} with {@code
 * findByUsername} (managed, cached {@code @Version} v) and then takes the per-user row lock via
 * {@link BalanceService#lockUserForUpdate}. When another order for the same user holds that lock,
 * this one waits for it to commit (row version v+1). Materializing the row through a locking query
 * then made Hibernate "upgrade" the lock mode of the already-managed entity and version-check the
 * cached v against v+1 — so every order that had to wait was rejected. Each test here recreates
 * that shape and asserts the order now waits and succeeds, with no lost update.
 *
 * <p>Runs on a real PostgreSQL ({@link DisposablePostgres}; skipped when none is available) with
 * the pool and the Hibernate second-level cache configured as in prod, so the cached-{@code User}
 * read path is covered too.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BalanceService.class, UserBalanceLockIntegrationTest.BalanceTransactionTemplates.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED) // each step owns its tx, as in prod
@EnabledIf(
        value = "postgresAvailable",
        disabledReason =
                "Needs PostgreSQL: Docker for Testcontainers, or DISPOSABLE_TEST_POSTGRES_URL")
class UserBalanceLockIntegrationTest {

    private static final int BURST = 24;

    private static boolean schemaRepaired;

    @Autowired private BalanceService balanceService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private BalanceAuditService balanceAuditService;

    private String username;
    private Long userId;
    private long initialVersion;

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean postgresAvailable() {
        return DisposablePostgres.isAvailable();
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        createEnumTypeStandIns();
        DisposablePostgres.registerDataSource(registry, DisposablePostgres.jdbcUrl());
        // Schema from the entity mappings: the Liquibase changelog does not apply to an empty
        // database (prod's schema has drifted from it), so it cannot build a test schema.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add(
                "spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Hibernate's ClassLoaderService cannot resolve the app's "classpath:ehcache-config.xml"
        // URI in a test JVM; hand it the file URL so the second-level cache stays on, as in prod.
        registry.add(
                "spring.jpa.properties.hibernate.javax.cache.uri",
                () ->
                        Objects.requireNonNull(
                                        UserBalanceLockIntegrationTest.class.getResource(
                                                "/ehcache-config.xml"),
                                        "ehcache-config.xml not on the test classpath")
                                .toString());
    }

    /**
     * Entities map several columns to PostgreSQL enum types that Liquibase creates in production
     * (columnDefinition {@code user_role}, {@code transaction_type}, ...). Hibernate's DDL cannot
     * create those, so stand them in as varchar domains — these tests never depend on the enum
     * value sets.
     */
    private static void createEnumTypeStandIns() {
        List<String> types =
                List.of(
                        "user_role",
                        "order_status",
                        "transaction_type",
                        "payment_status",
                        "audit_severity",
                        "audit_category");
        try (Connection connection = DisposablePostgres.openConnection();
                Statement statement = connection.createStatement()) {
            for (String type : types) {
                statement.execute(
                        "DO $$ BEGIN CREATE DOMAIN "
                                + type
                                + " AS varchar(64); EXCEPTION WHEN duplicate_object THEN NULL;"
                                + " END $$");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the test schema", e);
        }
    }

    /**
     * Hibernate's DDL gives the composite {@code (order_id, order_created_at)} reference from
     * balance_transactions to orders swapped column types (order_id becomes a timestamp), so no
     * charge could be recorded. Prod's Liquibase schema types them correctly. These tests never
     * link a charge to an order, so drop that foreign key and give the columns their real types.
     */
    private void repairBalanceTransactionOrderColumns() {
        if (schemaRepaired) {
            return;
        }
        // Pooled connections are non-autocommit (as in prod), so JDBC writes need a transaction.
        transaction()
                .executeWithoutResult(
                        status -> {
                            jdbc.execute(
                                    "DO $$ DECLARE fk text; BEGIN FOR fk IN SELECT conname"
                                            + " FROM pg_constraint"
                                            + " WHERE conrelid = 'balance_transactions'::regclass"
                                            + " AND confrelid = 'orders'::regclass LOOP"
                                            + " EXECUTE format('ALTER TABLE balance_transactions"
                                            + " DROP CONSTRAINT %I', fk); END LOOP; END $$");
                            jdbc.execute(
                                    "ALTER TABLE balance_transactions"
                                            + " ALTER COLUMN order_id TYPE bigint USING NULL,"
                                            + " ALTER COLUMN order_created_at TYPE timestamp(6)"
                                            + " USING NULL");
                        });
        schemaRepaired = true;
    }

    @TestConfiguration
    static class BalanceTransactionTemplates {
        @Bean("balanceTransactionTemplate")
        TransactionTemplate balanceTransactionTemplate(PlatformTransactionManager tm) {
            return new TransactionTemplate(tm);
        }

        @Bean("readOnlyTransactionTemplate")
        TransactionTemplate readOnlyTransactionTemplate(PlatformTransactionManager tm) {
            TransactionTemplate template = new TransactionTemplate(tm);
            template.setReadOnly(true);
            return template;
        }
    }

    @BeforeEach
    void prepareSchema() {
        repairBalanceTransactionOrderColumns();
    }

    @AfterEach
    void deleteTestUser() {
        if (userId != null) {
            transaction()
                    .executeWithoutResult(
                            status -> {
                                jdbc.update(
                                        "DELETE FROM balance_transactions WHERE user_id = ?",
                                        userId);
                                jdbc.update("DELETE FROM users WHERE id = ?", userId);
                            });
        }
    }

    // ------------------------------------------------------------------------------------------

    @Test
    void orderWhoseUserWentStaleBeforeTheLock_isChargedAgainstTheFreshBalance() {
        createUser("10.00");

        // Another order for the same user commits between our load and our lock.
        placeOrder("1.00", () -> inNewTransaction(() -> chargeElsewhere("2.00")), 0);

        assertBalanceAndVersion("7.00", 2);
    }

    @Test
    void orderWaitingOnAConcurrentOrdersRowLock_proceedsOnceThatOrderCommits() throws Exception {
        createUser("10.00");
        CountDownLatch weLoadedTheUser = new CountDownLatch(1);
        CountDownLatch otherOrderHoldsTheLock = new CountDownLatch(1);
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> otherOrder =
                    otherThread.submit(
                            () -> {
                                await(weLoadedTheUser);
                                chargeHoldingTheLockUntilAnotherOrderWaits(
                                        "2.00", otherOrderHoldsTheLock);
                            });

            placeOrder(
                    "1.00",
                    () -> {
                        weLoadedTheUser.countDown();
                        await(otherOrderHoldsTheLock);
                    },
                    0);

            otherOrder.get(30, TimeUnit.SECONDS);
        } finally {
            otherThread.shutdownNow();
        }

        assertBalanceAndVersion("7.00", 2);
    }

    @Test
    void burstOfConcurrentOrders_allSucceed_strictlySerializedWithoutLostUpdates()
            throws Exception {
        createUser("100.00");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(BURST);
        List<Future<?>> orders = new ArrayList<>();
        try {
            for (int i = 0; i < BURST; i++) {
                orders.add(
                        pool.submit(
                                () -> {
                                    await(start);
                                    // Hold the lock briefly, like createOrder's quota checks and
                                    // insert, so the orders genuinely queue on the row lock.
                                    placeOrder("0.25", () -> {}, 20);
                                }));
            }
            start.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<?> order : orders) {
                try {
                    order.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            assertThat(failures).as("orders rejected under contention").isEmpty();
        } finally {
            pool.shutdownNow();
        }

        assertBalanceAndVersion("94.00", BURST);

        // Strict serialization: each charge starts from exactly the balance the previous one left.
        List<BigDecimal[]> charges =
                jdbc.query(
                        "SELECT balance_before, balance_after FROM balance_transactions"
                                + " WHERE user_id = ? ORDER BY balance_after DESC",
                        (rs, n) ->
                                new BigDecimal[] {
                                    rs.getBigDecimal("balance_before"),
                                    rs.getBigDecimal("balance_after")
                                },
                        userId);
        assertThat(charges).hasSize(BURST);
        assertThat(charges.get(0)[0]).isEqualByComparingTo("100.00");
        for (int i = 1; i < charges.size(); i++) {
            assertThat(charges.get(i)[0]).isEqualByComparingTo(charges.get(i - 1)[1]);
        }
        assertThat(charges.get(BURST - 1)[1]).isEqualByComparingTo("94.00");
    }

    @Test
    void balanceCheckUsesTheFreshBalanceReadUnderTheLock() {
        createUser("5.00");

        // We loaded 5.00, but a concurrent order leaves only 1.00 — a 3.00 charge must be refused,
        // not approved against the stale 5.00 (and not fail with an optimistic-lock error).
        assertThatThrownBy(
                        () ->
                                placeOrder(
                                        "3.00",
                                        () -> inNewTransaction(() -> chargeElsewhere("4.00")),
                                        0))
                .isInstanceOf(InsufficientBalanceException.class);

        assertBalanceAndVersion("1.00", 1);
    }

    // ------------------------------------------------------------------------------------------

    /**
     * createOrder's shape: load the user with {@code findByUsername} (managed, cached
     * {@code @Version}), take the per-user lock, charge — all in one READ_COMMITTED transaction.
     */
    private void placeOrder(String amount, Runnable afterLoad, long workUnderLockMillis) {
        transaction()
                .executeWithoutResult(
                        status -> {
                            User user = loadUser();
                            afterLoad.run();
                            balanceService.lockUserForUpdate(user.getId());
                            sleep(workUnderLockMillis);
                            balanceService.deductBalance(
                                    user, new BigDecimal(amount), null, "order");
                        });
    }

    /**
     * A concurrent order that takes the user's row lock, then commits its charge only once another
     * session is blocked on that lock — the exact prod interleaving.
     */
    private void chargeHoldingTheLockUntilAnotherOrderWaits(
            String amount, CountDownLatch lockTaken) {
        transaction()
                .executeWithoutResult(
                        status -> {
                            User user = loadUser();
                            balanceService.lockUserForUpdate(user.getId());
                            lockTaken.countDown();
                            awaitSessionsWaitingOnALock(1);
                            balanceService.deductBalance(
                                    user, new BigDecimal(amount), null, "other order");
                        });
    }

    private void chargeElsewhere(String amount) {
        User user = userRepository.findById(userId).orElseThrow();
        balanceService.deductBalance(user, new BigDecimal(amount), null, "concurrent order");
    }

    private User loadUser() {
        return userRepository.findByUsername(username).orElseThrow();
    }

    private void createUser(String balance) {
        username = "lock-it-" + System.nanoTime();
        userId =
                transaction()
                        .execute(
                                status ->
                                        userRepository
                                                .save(
                                                        User.builder()
                                                                .username(username)
                                                                .email(username + "@test.local")
                                                                .passwordHash("x")
                                                                .balance(new BigDecimal(balance))
                                                                .totalSpent(BigDecimal.ZERO)
                                                                .role(UserRole.USER)
                                                                .isActive(true)
                                                                .emailVerified(true)
                                                                .build())
                                                .getId());
        initialVersion =
                jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, userId);
    }

    /** Checks the committed row, then the JPA read path (second-level cache) against it. */
    private void assertBalanceAndVersion(String expectedBalance, int expectedWrites) {
        BigDecimal rowBalance =
                jdbc.queryForObject(
                        "SELECT balance FROM users WHERE id = ?", BigDecimal.class, userId);
        Long rowVersion =
                jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, userId);
        assertThat(rowBalance).isEqualByComparingTo(expectedBalance);
        assertThat(rowVersion).isEqualTo(initialVersion + expectedWrites);

        User viaJpa = transaction().execute(s -> userRepository.findById(userId).orElseThrow());
        assertThat(viaJpa.getBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(viaJpa.getVersion()).isEqualTo(rowVersion);
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(30);
        return template;
    }

    private void inNewTransaction(Runnable work) {
        TransactionTemplate template = transaction();
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> work.run());
    }

    private void awaitSessionsWaitingOnALock(int sessions) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            // Only sessions blocked in the user-row lock statement itself — not any unrelated
            // session of the shared database that happens to wait on some lock. Leading % allows
            // the "/* dynamic native SQL query */" prefix Hibernate adds when SQL comments are on.
            Integer waiting =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity"
                                    + " WHERE wait_event_type = 'Lock'"
                                    + " AND datname = current_database()"
                                    + " AND query LIKE '%SELECT id FROM users WHERE id = % FOR NO"
                                    + " KEY UPDATE'",
                            Integer.class);
            if (waiting != null && waiting >= sessions) {
                return;
            }
            sleep(10);
        }
        throw new IllegalStateException(
                "No session started waiting on the user row lock; sessions waiting on a lock: "
                        + jdbc.queryForList(
                                "SELECT query FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                                        + " AND datname = current_database()",
                                String.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other order");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

package com.smmpanel.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.smmpanel.support.DisposablePostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PartitionMaintenanceScheduler} against a real PostgreSQL whose pool hands out
 * non-autocommit connections, as in prod. There, the scheduler's DDL used to be rolled back when
 * the connection returned to the pool — it logged "Ensured partition" nightly while no partition
 * past 2026-12 was ever created, which would have failed every order insert from 2027-01-01.
 *
 * <p>The partitioned parents are minimal stand-ins for {@code orders}/{@code operator_logs} in a
 * dedicated schema, each holding only the current month (as the Liquibase-created partitions do).
 * Sessions run in a non-UTC time zone to prove the new bounds adjoin the existing UTC ones
 * regardless of the session TimeZone.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PartitionMaintenanceScheduler.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // the scheduler owns its transactions
@EnabledIf(
        value = "postgresAvailable",
        disabledReason =
                "Needs PostgreSQL: Docker for Testcontainers, or DISPOSABLE_TEST_POSTGRES_URL")
class PartitionMaintenanceSchedulerIntegrationTest {

    private static final String SCHEMA = "partition_maintenance_it";
    private static final List<String> PARENTS = List.of("orders", "operator_logs");
    private static final int MONTHS_AHEAD = 6; // the scheduler's default
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

    @Autowired private PartitionMaintenanceScheduler scheduler;

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean postgresAvailable() {
        return DisposablePostgres.isAvailable();
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        createPartitionedParents();
        String url = DisposablePostgres.jdbcUrl();
        DisposablePostgres.registerDataSource(
                registry, url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add(
                "spring.datasource.hikari.connection-init-sql",
                () -> "SET TIME ZONE 'Europe/Chisinau'");
    }

    private static void createPartitionedParents() {
        LocalDate thisMonth = currentMonth();
        try (Connection connection = DisposablePostgres.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA);
            for (String parent : PARENTS) {
                statement.execute(
                        "CREATE TABLE "
                                + SCHEMA
                                + "."
                                + parent
                                + " (id bigint, created_at timestamptz NOT NULL)"
                                + " PARTITION BY RANGE (created_at)");
                statement.execute(
                        String.format(
                                "CREATE TABLE %s.%s_%s PARTITION OF %s.%s FOR VALUES FROM"
                                        + " ('%s 00:00:00+00') TO ('%s 00:00:00+00')",
                                SCHEMA,
                                parent,
                                thisMonth.format(SUFFIX),
                                SCHEMA,
                                parent,
                                thisMonth,
                                thisMonth.plusMonths(1)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare the partitioned tables", e);
        }
    }

    @Test
    void startupSweep_commitsTheComingMonths_adjoiningTheExistingUtcPartition()
            throws SQLException {
        // The startup sweep (@PostConstruct) already ran when the context started. Look from a
        // separate session: only committed partitions are visible there.
        for (String parent : PARENTS) {
            List<String> expected = new ArrayList<>();
            for (int offset = 0; offset <= MONTHS_AHEAD; offset++) {
                LocalDate from = currentMonth().plusMonths(offset);
                expected.add(
                        String.format(
                                "%s_%s FOR VALUES FROM ('%s 00:00:00+00') TO ('%s 00:00:00+00')",
                                parent, from.format(SUFFIX), from, from.plusMonths(1)));
            }
            assertThat(partitionsOf(parent)).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void rowsUpToTheLastStagedMonthAreAccepted() throws SQLException {
        LocalDate lastStagedMonth = currentMonth().plusMonths(MONTHS_AHEAD);
        String lastInstant = lastStagedMonth.plusMonths(1) + " 00:00:00+00";
        try (Connection connection = DisposablePostgres.openConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String parent : PARENTS) {
                    statement.execute(
                            String.format(
                                    "INSERT INTO %s.%s VALUES (1, '%s 00:00:00+00'),"
                                            + " (2, timestamptz '%s' - interval '1 microsecond')",
                                    SCHEMA, parent, currentMonth(), lastInstant));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void rerunIsANoOp() throws SQLException {
        List<List<String>> before = new ArrayList<>();
        for (String parent : PARENTS) {
            before.add(partitionsOf(parent));
        }

        scheduler.ensureFuturePartitions();

        for (int i = 0; i < PARENTS.size(); i++) {
            assertThat(partitionsOf(PARENTS.get(i))).isEqualTo(before.get(i));
        }
    }

    /** Committed partitions of a parent with their bounds rendered in UTC, oldest first. */
    private static List<String> partitionsOf(String parent) throws SQLException {
        try (Connection connection = DisposablePostgres.openConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'UTC'");
            }
            try (PreparedStatement query =
                    connection.prepareStatement(
                            "SELECT c.relname || ' ' || pg_get_expr(c.relpartbound, c.oid)"
                                    + " FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                                    + " WHERE i.inhparent = to_regclass(?) ORDER BY c.relname")) {
                query.setString(1, SCHEMA + "." + parent);
                List<String> partitions = new ArrayList<>();
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        partitions.add(rows.getString(1));
                    }
                }
                return partitions;
            }
        }
    }

    /** The scheduler's notion of "this month" (JVM default zone). */
    private static LocalDate currentMonth() {
        return LocalDate.now().withDayOfMonth(1);
    }
}

package com.smmpanel.service.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.smmpanel.entity.OrderStatus;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objenesis.ObjenesisStd;

/**
 * Pinpoint coverage for {@link OrderService#mapFromPerfectPanelStatus(String)}.
 *
 * <p>The method has a regression history — it used to default to {@link OrderStatus#PENDING} on any
 * unknown input, which silently changed the user's filter. We now want exhaustive coverage of:
 *
 * <ul>
 *   <li>Every native enum value parses (case-insensitive, underscore-tolerant).
 *   <li>Every PerfectPanel-style alias maps to the canonical enum.
 *   <li>Unknown / blank / null returns {@code null} (= "no filter"), never silently defaults.
 * </ul>
 *
 * The method is private, so we use reflection. We bypass instance creation by passing {@code null}
 * since the method does not touch instance state — see {@link Method#invoke(Object, Object...)}.
 */
class OrderServiceStatusMappingTest {

    private static Method mapMethod;

    @BeforeAll
    static void resolveMethod() throws Exception {
        mapMethod = OrderService.class.getDeclaredMethod("mapFromPerfectPanelStatus", String.class);
        mapMethod.setAccessible(true);
    }

    /**
     * OrderService has 19 deps; the mapper does not touch instance state, so we allocate a bare
     * instance via Objenesis (transitively available through Mockito) and invoke the private mapper
     * directly. This keeps the test surgical and free of integration-test cost.
     */
    private static final Object BARE = new ObjenesisStd().newInstance(OrderService.class);

    private OrderStatus call(String input) throws Exception {
        return (OrderStatus) mapMethod.invoke(BARE, input);
    }

    // -----------------------------------------------------------------
    // Native enum names — every OrderStatus value must round-trip.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Every native OrderStatus value maps to itself (case-insensitive)")
    void nativeEnumValues_round_trip() throws Exception {
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(call(s.name())).as("UPPER %s", s).isEqualTo(s);
            assertThat(call(s.name().toLowerCase())).as("lower %s", s).isEqualTo(s);
        }
    }

    @Test
    @DisplayName(
            "IN_PROGRESS native value maps to IN_PROGRESS (regression: was silently → PENDING)")
    void inProgress_native_does_not_silently_become_pending() throws Exception {
        assertThat(call("IN_PROGRESS")).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(call("in_progress")).isEqualTo(OrderStatus.IN_PROGRESS);
    }

    // -----------------------------------------------------------------
    // PerfectPanel aliases — historical reseller integrations.
    // -----------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "in progress,IN_PROGRESS",
        "In Progress,IN_PROGRESS",
        "inprogress,IN_PROGRESS",
        "in_progress,IN_PROGRESS",
        "pending,PENDING",
        "processing,PROCESSING",
        "active,ACTIVE",
        "partial,PARTIAL",
        "completed,COMPLETED",
        "Completed,COMPLETED",
        "canceled,CANCELLED",
        "cancelled,CANCELLED",
        "Canceled,CANCELLED",
        "paused,PAUSED",
        "holding,HOLDING",
        "refill,REFILL",
        "error,ERROR",
        "suspended,SUSPENDED"
    })
    void perfectPanelAliases_resolve_to_native_enum(String alias, OrderStatus expected)
            throws Exception {
        assertThat(call(alias)).isEqualTo(expected);
    }

    // -----------------------------------------------------------------
    // Unknown / blank / null → null (= "no filter"), never silently defaults to PENDING.
    // -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "garbage", "in_flight", "??", "  banana  ", "0", "1"})
    void unknownValue_returnsNull(String input) throws Exception {
        assertThat(call(input)).isNull();
    }

    @Test
    @DisplayName("null returns null (= no filter)")
    void nullReturnsNull() throws Exception {
        assertThat(call(null)).isNull();
    }

    @Test
    @DisplayName("blank/whitespace returns null (= no filter)")
    void blankReturnsNull() throws Exception {
        assertThat(call("")).isNull();
        assertThat(call("   ")).isNull();
        assertThat(call("\t")).isNull();
    }
}

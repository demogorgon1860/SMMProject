package com.smmpanel.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Coverage for {@link OrderSearchUtil#parseOrderIds} — the comma/space order-id list parser. */
class OrderSearchUtilTest {

    @Test
    @DisplayName("single numeric id parses to a 1-element list (backwards compatible)")
    void single_id() {
        assertThat(OrderSearchUtil.parseOrderIds("29931")).containsExactly(29931L);
        assertThat(OrderSearchUtil.parseOrderIds("  29931  ")).containsExactly(29931L);
    }

    @Test
    @DisplayName("comma / space / mixed separators all parse to the full id list")
    void multi_id() {
        assertThat(OrderSearchUtil.parseOrderIds("29931, 29932")).containsExactly(29931L, 29932L);
        assertThat(OrderSearchUtil.parseOrderIds("29931 29932 29933"))
                .containsExactly(29931L, 29932L, 29933L);
        assertThat(OrderSearchUtil.parseOrderIds("29931,29932,  29933 "))
                .containsExactly(29931L, 29932L, 29933L);
    }

    @Test
    @DisplayName("duplicates are collapsed, input order preserved")
    void dedupe_preserves_order() {
        assertThat(OrderSearchUtil.parseOrderIds("3, 1, 3, 2, 1")).containsExactly(3L, 1L, 2L);
    }

    @Test
    @DisplayName("any non-numeric token → empty (caller falls back to link/username search)")
    void non_numeric_falls_back() {
        assertThat(OrderSearchUtil.parseOrderIds("instagram.com/p/abc")).isEmpty();
        assertThat(OrderSearchUtil.parseOrderIds("29931, abc")).isEmpty();
        assertThat(OrderSearchUtil.parseOrderIds("john_doe")).isEmpty();
    }

    @Test
    @DisplayName("zero / negative ids are not valid order ids → empty")
    void zero_and_negative_rejected() {
        assertThat(OrderSearchUtil.parseOrderIds("0")).isEmpty();
        assertThat(OrderSearchUtil.parseOrderIds("-5")).isEmpty();
        assertThat(OrderSearchUtil.parseOrderIds("29931, 0")).isEmpty();
    }

    @Test
    @DisplayName("null / blank / separators-only → empty")
    void blank_inputs() {
        assertThat(OrderSearchUtil.parseOrderIds(null)).isEmpty();
        assertThat(OrderSearchUtil.parseOrderIds("")).isEmpty();
        assertThat(OrderSearchUtil.parseOrderIds("   ")).isEmpty();
        assertThat(OrderSearchUtil.parseOrderIds(" , , ")).isEmpty();
    }

    @Test
    @DisplayName("result is immutable-safe: returns a usable List for callers")
    void returns_list() {
        List<Long> ids = OrderSearchUtil.parseOrderIds("1,2,3");
        assertThat(ids).hasSize(3);
    }

    @Test
    @DisplayName("a huge paste-list is capped at MAX_IDS (bounds the IN clause)")
    void caps_oversized_list() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= OrderSearchUtil.MAX_IDS + 500; i++) {
            if (i > 1) sb.append(',');
            sb.append(i);
        }
        List<Long> ids = OrderSearchUtil.parseOrderIds(sb.toString());
        assertThat(ids).hasSize(OrderSearchUtil.MAX_IDS);
        assertThat(ids.get(0)).isEqualTo(1L);
        assertThat(ids.get(OrderSearchUtil.MAX_IDS - 1)).isEqualTo((long) OrderSearchUtil.MAX_IDS);
    }
}

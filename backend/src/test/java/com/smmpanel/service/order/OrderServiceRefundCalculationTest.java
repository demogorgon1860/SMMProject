package com.smmpanel.service.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Edge-case coverage for {@link OrderService#calculateRefund(BigDecimal, int, Integer)}. The five
 * cases below correspond exactly to the audit checklist in task 16.6 — each is the kind of silent
 * bug that bites at month 6: a single corrupted order leaks money, an overdelivery causes a
 * negative refund, a divide-by-zero crashes a cancellation flow.
 */
class OrderServiceRefundCalculationTest {

    private static final BigDecimal CHARGE = new BigDecimal("10.00");

    @Test
    @DisplayName("completed=0 → full refund")
    void nothingDeliveredYet_refundsFullCharge() {
        // remains == quantity means nothing was delivered; user gets the whole charge back.
        BigDecimal refund = OrderService.calculateRefund(CHARGE, 1000, 1000);
        assertThat(refund).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("completed=quantity → zero refund")
    void fullyDelivered_returnsZero() {
        BigDecimal refund = OrderService.calculateRefund(CHARGE, 1000, 0);
        assertThat(refund).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("partial delivery → proportional refund (rounded to cents)")
    void partialDelivery_returnsProportionalRefund() {
        // 600 of 1000 delivered → 400 remains → 40% of $10 = $4.00
        BigDecimal refund = OrderService.calculateRefund(CHARGE, 1000, 400);
        assertThat(refund).isEqualByComparingTo("4.00");
    }

    @Test
    @DisplayName("completed > quantity (overdelivery) → never refund more than charge")
    void overdelivery_clampedToZero() {
        // Bot delivered MORE than asked. `remains` is conventionally clamped to 0 in this case;
        // belt-and-suspenders: a stray negative value must NOT yield a negative-sign refund.
        BigDecimal negativeRemains = OrderService.calculateRefund(CHARGE, 1000, -50);
        assertThat(negativeRemains).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("quantity = 0 → zero refund (no divide-by-zero)")
    void zeroQuantity_returnsZero() {
        // Validators block this on order creation, but a corrupt import or manual SQL edit
        // could produce it. The previous implementation threw ArithmeticException here.
        BigDecimal refund = OrderService.calculateRefund(CHARGE, 0, 100);
        assertThat(refund).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("BigDecimal precision: fractional ratio rounds to cents (HALF_UP)")
    void fractionalRatio_roundsToCents() {
        // 1 / 3 of $10 = $3.333... → $3.33 with HALF_UP
        // Direct double arithmetic would yield 3.3333333333..., which can't be stored in
        // decimal(10,2); without an explicit setScale the JPA write would either truncate
        // silently or throw a HibernateException depending on dialect strictness.
        BigDecimal refund = OrderService.calculateRefund(new BigDecimal("10.00"), 3, 1);
        assertThat(refund).isEqualByComparingTo("3.33");
        assertThat(refund.scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("BigDecimal precision: 0.1 + 0.2 != 0.3 trap")
    void floatingPointTrap_returnsExactCents() {
        // The classic floating-point trap. Refund of $0.30 on $0.30 paid (charge=0.30,
        // quantity=10, remains=10) — must come back as exactly 0.30, not 0.29 or 0.30000004.
        BigDecimal refund = OrderService.calculateRefund(new BigDecimal("0.30"), 10, 10);
        assertThat(refund).isEqualByComparingTo("0.30");
    }

    @Test
    @DisplayName("null charge → zero refund")
    void nullCharge_returnsZero() {
        BigDecimal refund = OrderService.calculateRefund(null, 1000, 500);
        assertThat(refund).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("null remains → zero refund (defensive — entity column is nullable)")
    void nullRemains_returnsZero() {
        BigDecimal refund = OrderService.calculateRefund(CHARGE, 1000, null);
        assertThat(refund).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("remains > quantity → full charge (never refund more than was paid)")
    void remainsExceedsQuantity_clampedToFullCharge() {
        // Defensive: a corrupt order with remains > quantity must not produce a refund > charge.
        BigDecimal refund = OrderService.calculateRefund(CHARGE, 1000, 1500);
        assertThat(refund).isEqualByComparingTo("10.00");
    }
}

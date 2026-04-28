package com.smmpanel.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.admin.DailyStatPoint;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.BalanceDepositRepository;
import com.smmpanel.repository.jpa.ConversionCoefficientRepository;
import com.smmpanel.repository.jpa.OperatorLogRepository;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.repository.jpa.ServiceRepository;
import com.smmpanel.repository.jpa.UserRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.core.AuditService;
import com.smmpanel.service.notification.DailyProfitService;
import com.smmpanel.service.notification.TelegramNotificationService;
import com.smmpanel.service.order.state.OrderStateManager;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Coverage for {@link AdminService#getDailyStats(int)} — the daily order/revenue series consumed by
 * the admin dashboard.
 *
 * <p>The method has tricky behavior worth pinning down:
 *
 * <ul>
 *   <li>Returns exactly N entries (no gaps, no extras), one per calendar day from {@code today - N
 *       + 1} through {@code today}, sorted ascending by date.
 *   <li>Days with zero orders come back as zero rows (not omitted).
 *   <li>Per-day buckets count COMPLETED, PARTIAL and CANCELLED+ERROR; revenue counts only
 *       COMPLETED+PARTIAL.
 *   <li>Out-of-window rows from the SQL aggregate (e.g. tz boundary slop) are silently dropped
 *       rather than blowing up.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceDailyStatsTest {

    @Mock private OrderRepository orderRepository;
    @Mock private UserRepository userRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private ConversionCoefficientRepository coefficientRepository;
    @Mock private OperatorLogRepository operatorLogRepository;
    @Mock private BalanceService balanceService;
    @Mock private OrderStateManager orderStateManager;
    @Mock private AuditService auditService;
    @Mock private BalanceDepositRepository balanceDepositRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private InstagramBotClient instagramBotClient;
    @Mock private DailyProfitService dailyProfitService;
    @Mock private TelegramNotificationService telegramNotificationService;

    @InjectMocks private AdminService adminService;

    @Test
    @DisplayName(
            "getDailyStats(7): returns exactly 7 zero-filled days when DB is empty, sorted asc")
    void zero_fill_when_no_rows() {
        when(orderRepository.getDailyOrderBreakdown(any())).thenReturn(List.of());

        List<DailyStatPoint> result = adminService.getDailyStats(7);

        assertThat(result).hasSize(7);
        // Sorted ascending by date.
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).getDate()).isAfter(result.get(i - 1).getDate());
        }
        // Last entry is today.
        assertThat(result.get(result.size() - 1).getDate()).isEqualTo(LocalDate.now());
        // First entry is today - 6.
        assertThat(result.get(0).getDate()).isEqualTo(LocalDate.now().minusDays(6));
        // All zero.
        for (DailyStatPoint p : result) {
            assertThat(p.getTotal()).isZero();
            assertThat(p.getCompleted()).isZero();
            assertThat(p.getPartial()).isZero();
            assertThat(p.getCancelled()).isZero();
            assertThat(p.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("getDailyStats: COMPLETED counts toward revenue + completed bucket")
    void completed_rows_aggregate() {
        LocalDate today = LocalDate.now();
        when(orderRepository.getDailyOrderBreakdown(any()))
                .thenReturn(
                        java.util.Collections.singletonList(
                                new Object[] {
                                    Date.valueOf(today),
                                    OrderStatus.COMPLETED,
                                    3L,
                                    new BigDecimal("12.50")
                                }));

        List<DailyStatPoint> result = adminService.getDailyStats(7);
        DailyStatPoint todayPoint = result.get(result.size() - 1);

        assertThat(todayPoint.getTotal()).isEqualTo(3L);
        assertThat(todayPoint.getCompleted()).isEqualTo(3L);
        assertThat(todayPoint.getPartial()).isZero();
        assertThat(todayPoint.getCancelled()).isZero();
        assertThat(todayPoint.getRevenue()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName(
            "getDailyStats: PARTIAL is treated as realized revenue (matches DailyProfitService)")
    void partial_counted_as_revenue() {
        LocalDate today = LocalDate.now();
        when(orderRepository.getDailyOrderBreakdown(any()))
                .thenReturn(
                        java.util.Collections.singletonList(
                                new Object[] {
                                    Date.valueOf(today),
                                    OrderStatus.PARTIAL,
                                    1L,
                                    new BigDecimal("4.20")
                                }));

        DailyStatPoint p = adminService.getDailyStats(3).get(2);
        assertThat(p.getPartial()).isEqualTo(1L);
        assertThat(p.getTotal()).isEqualTo(1L);
        assertThat(p.getRevenue()).isEqualByComparingTo("4.20");
    }

    @Test
    @DisplayName("getDailyStats: CANCELLED and ERROR add to cancelled bucket but NOT to revenue")
    void cancelled_and_error_no_revenue() {
        LocalDate today = LocalDate.now();
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(
                new Object[] {
                    Date.valueOf(today), OrderStatus.CANCELLED, 2L, new BigDecimal("9.99")
                });
        rows.add(new Object[] {Date.valueOf(today), OrderStatus.ERROR, 1L, new BigDecimal("5.00")});
        when(orderRepository.getDailyOrderBreakdown(any())).thenReturn(rows);

        DailyStatPoint p = adminService.getDailyStats(3).get(2);
        assertThat(p.getCancelled()).isEqualTo(3L);
        assertThat(p.getTotal()).isEqualTo(3L);
        assertThat(p.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName(
            "getDailyStats: IN_PROGRESS / PENDING count toward total only, not the named buckets")
    void in_flight_only_total() {
        LocalDate today = LocalDate.now();
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(
                new Object[] {
                    Date.valueOf(today), OrderStatus.IN_PROGRESS, 5L, new BigDecimal("0")
                });
        rows.add(new Object[] {Date.valueOf(today), OrderStatus.PENDING, 2L, new BigDecimal("0")});
        when(orderRepository.getDailyOrderBreakdown(any())).thenReturn(rows);

        DailyStatPoint p = adminService.getDailyStats(3).get(2);
        assertThat(p.getTotal()).isEqualTo(7L);
        assertThat(p.getCompleted()).isZero();
        assertThat(p.getPartial()).isZero();
        assertThat(p.getCancelled()).isZero();
        assertThat(p.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName(
            "getDailyStats: tolerates LocalDate raw (some Hibernate dialects return LocalDate)")
    void tolerates_localdate_raw_form() {
        LocalDate today = LocalDate.now();
        when(orderRepository.getDailyOrderBreakdown(any()))
                .thenReturn(
                        java.util.Collections.singletonList(
                                new Object[] {
                                    today, OrderStatus.COMPLETED, 1L, new BigDecimal("1.00")
                                }));

        DailyStatPoint p = adminService.getDailyStats(3).get(2);
        assertThat(p.getCompleted()).isEqualTo(1L);
    }

    @Test
    @DisplayName("getDailyStats: drops rows whose date falls outside the requested window")
    void out_of_window_rows_dropped() {
        LocalDate ancient = LocalDate.now().minusDays(100);
        when(orderRepository.getDailyOrderBreakdown(any()))
                .thenReturn(
                        java.util.Collections.singletonList(
                                new Object[] {
                                    Date.valueOf(ancient),
                                    OrderStatus.COMPLETED,
                                    99L,
                                    new BigDecimal("999")
                                }));

        List<DailyStatPoint> result = adminService.getDailyStats(7);
        for (DailyStatPoint p : result) {
            assertThat(p.getCompleted()).isZero();
            assertThat(p.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("getDailyStats: null revenue from SQL aggregate is treated as zero (not NPE)")
    void null_revenue_treated_as_zero() {
        LocalDate today = LocalDate.now();
        when(orderRepository.getDailyOrderBreakdown(any()))
                .thenReturn(
                        java.util.Collections.singletonList(
                                new Object[] {
                                    Date.valueOf(today), OrderStatus.COMPLETED, 1L, null
                                }));

        DailyStatPoint p = adminService.getDailyStats(3).get(2);
        assertThat(p.getCompleted()).isEqualTo(1L);
        assertThat(p.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName(
            "getDailyStats(30): returns 30 entries — confirms the days param drives the window")
    void days_param_drives_window_size() {
        when(orderRepository.getDailyOrderBreakdown(any())).thenReturn(List.of());
        assertThat(adminService.getDailyStats(30)).hasSize(30);
        assertThat(adminService.getDailyStats(1)).hasSize(1);
    }
}

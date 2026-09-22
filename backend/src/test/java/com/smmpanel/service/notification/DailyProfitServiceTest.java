package com.smmpanel.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.entity.DailyProfitSummary;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.DailyProfitSummaryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Day-boundary tests for {@link DailyProfitService}: a profit "day" is a calendar day in Moldova
 * (Europe/Chisinau — EEST, UTC+3 in summer; EET, UTC+2 in winter), not the container's UTC day.
 */
class DailyProfitServiceTest {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private DailyProfitSummaryRepository repo;
    private TelegramBotProperties props;
    private DailyProfitService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        repo = mock(DailyProfitSummaryRepository.class);
        props = new TelegramBotProperties();
        service = new DailyProfitService(redis, repo, props);
        service.init();
    }

    private void nowIs(String isoInstant) {
        nowIs(service, isoInstant);
    }

    private static void nowIs(DailyProfitService svc, String isoInstant) {
        svc.setClock(Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC));
    }

    @Test
    void summer_profitRightAtMoldovaMidnight_countsIntoTheNewLocalDay() {
        nowIs("2026-09-21T21:00:00Z"); // 00:00 EEST on 22.09 — still 21.09 in UTC
        service.recordProfit(new BigDecimal("1.50"), OrderStatus.COMPLETED);

        verify(hashOps).increment("telegram:profit:2026-09-22", "total", 1.5d);
        verify(hashOps).increment("telegram:profit:2026-09-22", "completed_count", 1L);
        verify(redis).expire("telegram:profit:2026-09-22", 8L, TimeUnit.DAYS);
    }

    @Test
    void summer_profitJustBeforeMoldovaMidnight_staysOnTheSameLocalDay() {
        nowIs("2026-09-21T20:59:59Z"); // 23:59:59 EEST on 21.09
        service.recordProfit(new BigDecimal("2.00"), OrderStatus.PARTIAL);

        verify(hashOps).increment("telegram:profit:2026-09-21", "total", 2.0d);
        verify(hashOps).increment("telegram:profit:2026-09-21", "partial_count", 1L);
    }

    @Test
    void winter_dayBoundaryFollowsDstBackToUtcPlus2() {
        nowIs("2026-12-10T21:59:59Z"); // 23:59:59 EET on 10.12
        assertThat(service.currentBusinessDay()).isEqualTo(LocalDate.of(2026, 12, 10));

        nowIs("2026-12-10T22:00:00Z"); // 00:00 EET on 11.12
        assertThat(service.currentBusinessDay()).isEqualTo(LocalDate.of(2026, 12, 11));
    }

    @Test
    void bucketingFollowsTheConfiguredZone_notTheJvmDefaultOrUtc() {
        // Tokyo midnight (15:00Z) is 18:00 in Moldova and 15:00 in UTC — both still the 21st — so
        // a regression to ZoneId.systemDefault() (Europe/Chisinau on the dev box) or UTC fails
        // here.
        props.getProfit().setZone("Asia/Tokyo");
        DailyProfitService tokyo = new DailyProfitService(redis, repo, props);
        tokyo.init();

        nowIs(tokyo, "2026-09-21T15:00:00Z");
        tokyo.recordProfit(new BigDecimal("1.00"), OrderStatus.COMPLETED);
        verify(hashOps).increment("telegram:profit:2026-09-22", "total", 1.0d);

        nowIs(tokyo, "2026-09-21T14:59:59Z");
        assertThat(tokyo.currentBusinessDay()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void midnightRun_closesTheDayThatJustEnded_evenIfItStartsEarlyOrLate() {
        LocalDate sep21 = LocalDate.of(2026, 9, 21);
        nowIs("2026-09-21T21:00:00Z"); // on time: 00:00 EEST on 22.09
        assertThat(service.dayClosedByMidnightRun()).isEqualTo(sep21);
        nowIs("2026-09-21T20:59:59.990Z"); // 10ms early (wall clock stepped back)
        assertThat(service.dayClosedByMidnightRun()).isEqualTo(sep21);
        nowIs("2026-09-22T06:00:00Z"); // 9h late (09:00 in Moldova)
        assertThat(service.dayClosedByMidnightRun()).isEqualTo(sep21);

        LocalDate dec10 = LocalDate.of(2026, 12, 10);
        nowIs("2026-12-10T22:00:00Z"); // on time: 00:00 EET on 11.12
        assertThat(service.dayClosedByMidnightRun()).isEqualTo(dec10);
        nowIs("2026-12-10T21:59:59.990Z"); // 10ms early
        assertThat(service.dayClosedByMidnightRun()).isEqualTo(dec10);
    }

    @Test
    void lastClosedBusinessDay_isYesterdayInMoldova() {
        nowIs("2026-09-21T21:30:00Z"); // 00:30 EEST on 22.09
        assertThat(service.lastClosedBusinessDay()).isEqualTo(LocalDate.of(2026, 9, 21));

        nowIs("2026-09-22T10:00:00Z"); // 13:00 EEST on 22.09
        assertThat(service.lastClosedBusinessDay()).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void reportText_readsTheRequestedDayAndPrintsItsDate() {
        stubDay("2026-09-21", "346.3199999999", "667", null);

        String text = service.buildDailyReportText(LocalDate.of(2026, 9, 21));

        assertThat(text)
                .isEqualTo(
                        String.join(
                                System.lineSeparator(),
                                "💰 Сутки завершены! (21.09.2026)",
                                "Выполнено: 667 (полных: 667, частичных: 0)",
                                "Профит: $346.32"));
    }

    @Test
    void persist_upsertsTheRowForTheRequestedDay() {
        LocalDate day = LocalDate.of(2026, 9, 21);
        stubDay("2026-09-21", "10.005", "3", "1");
        when(repo.findByReportDate(day)).thenReturn(Optional.empty());

        service.persistDailyReport(day);

        ArgumentCaptor<DailyProfitSummary> saved =
                ArgumentCaptor.forClass(DailyProfitSummary.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getReportDate()).isEqualTo(day);
        assertThat(saved.getValue().getTotalProfit()).isEqualByComparingTo(new BigDecimal("10.01"));
        assertThat(saved.getValue().getCompletedCount()).isEqualTo(3);
        assertThat(saved.getValue().getPartialCount()).isEqualTo(1);
    }

    @Test
    void catchUpProbes_reflectTheSummaryRowAndTheRedisCounters() {
        LocalDate day = LocalDate.of(2026, 9, 21);
        when(repo.findByReportDate(day))
                .thenReturn(Optional.of(DailyProfitSummary.builder().reportDate(day).build()));
        when(redis.hasKey("telegram:profit:2026-09-21")).thenReturn(true);

        assertThat(service.hasPersistedReport(day)).isTrue();
        assertThat(service.hasCounters(day)).isTrue();

        LocalDate other = LocalDate.of(2026, 9, 20);
        when(repo.findByReportDate(other)).thenReturn(Optional.empty());
        assertThat(service.hasPersistedReport(other)).isFalse();
        assertThat(service.hasCounters(other)).isFalse(); // no such key in Redis
    }

    @Test
    void unknownZone_failsFastAtStartup() {
        props.getProfit().setZone("Mars/Olympus_Mons");
        DailyProfitService misconfigured = new DailyProfitService(redis, repo, props);

        assertThatThrownBy(misconfigured::init).isInstanceOf(DateTimeException.class);
    }

    private void stubDay(String date, String total, String completed, String partial) {
        String key = "telegram:profit:" + date;
        when(hashOps.get(key, "total")).thenReturn(total);
        when(hashOps.get(key, "completed_count")).thenReturn(completed);
        when(hashOps.get(key, "partial_count")).thenReturn(partial);
    }
}

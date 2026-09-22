package com.smmpanel.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.integration.InstagramService;
import com.smmpanel.service.notification.CancelDecisionService;
import com.smmpanel.service.notification.DailyProfitService;
import com.smmpanel.service.notification.TelegramBotService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * Unit tests for the daily profit report job and its startup catch-up in {@link TelegramScheduler}.
 */
class TelegramSchedulerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private TelegramBotProperties props;
    private DailyProfitService profit;
    private TelegramBotService tg;
    private TelegramScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new TelegramBotProperties();
        profit = mock(DailyProfitService.class);
        tg = mock(TelegramBotService.class);
        when(profit.dayClosedByMidnightRun()).thenReturn(DAY);
        when(profit.lastClosedBusinessDay()).thenReturn(DAY);
        when(profit.buildDailyReportText(DAY)).thenReturn("report");

        scheduler =
                new TelegramScheduler(
                        profit,
                        tg,
                        mock(CancelDecisionService.class),
                        mock(OrderRepository.class),
                        mock(InstagramService.class),
                        props,
                        mock(InstagramBotClient.class),
                        mock(ObjectProvider.class));
    }

    // ---------------- midnight run ----------------

    @Test
    void reportsAndPersistsTheDayThatJustEnded() {
        scheduler.sendDailyReport();

        verify(profit).persistDailyReport(DAY);
        verify(tg).sendPlainMessage("report");
    }

    @Test
    void telegramFailureDoesNotLoseThePersistedSummary() {
        doThrow(new RuntimeException("telegram down")).when(tg).sendPlainMessage(anyString());

        scheduler.sendDailyReport();

        verify(profit).persistDailyReport(DAY);
    }

    @Test
    void persistFailureStillSendsTheReport() {
        doThrow(new RuntimeException("db down")).when(profit).persistDailyReport(any());

        scheduler.sendDailyReport();

        verify(tg).sendPlainMessage("report");
    }

    @Test
    void disabledTelegramSkipsTheJob() {
        props.setEnabled(false);

        scheduler.sendDailyReport();
        scheduler.catchUpMissedDailyReport();

        verify(profit, never()).persistDailyReport(any());
        verifyNoInteractions(tg);
    }

    // ---------------- startup catch-up ----------------

    @Test
    void catchUp_reportsADayWhoseMidnightRunWasMissed() {
        when(profit.hasPersistedReport(DAY)).thenReturn(false);
        when(profit.hasCounters(DAY)).thenReturn(true);

        scheduler.catchUpMissedDailyReport();

        verify(profit).persistDailyReport(DAY);
        verify(tg).sendPlainMessage("report");
    }

    @Test
    void catchUp_leavesAnAlreadyReportedDayAlone() {
        when(profit.hasPersistedReport(DAY)).thenReturn(true);
        when(profit.hasCounters(DAY)).thenReturn(true);

        scheduler.catchUpMissedDailyReport();

        verify(profit, never()).persistDailyReport(any());
        verifyNoInteractions(tg);
    }

    @Test
    void catchUp_skipsADayWithNoCounters() {
        when(profit.hasPersistedReport(DAY)).thenReturn(false);
        when(profit.hasCounters(DAY)).thenReturn(false);

        scheduler.catchUpMissedDailyReport();

        verify(profit, never()).persistDailyReport(any());
        verifyNoInteractions(tg);
    }

    @Test
    void catchUpRacingTheMidnightRun_reportsTheDayOnce() {
        // Startup lands on midnight: the cron already reported the day, but the catch-up's
        // summary check ran before that persist committed.
        when(profit.hasPersistedReport(DAY)).thenReturn(false);
        when(profit.hasCounters(DAY)).thenReturn(true);

        scheduler.sendDailyReport();
        scheduler.catchUpMissedDailyReport();

        verify(profit, times(1)).persistDailyReport(DAY);
        verify(tg, times(1)).sendPlainMessage(anyString());
    }

    @Test
    void catchUpFailureNeverBreaksStartup() {
        when(profit.lastClosedBusinessDay()).thenThrow(new RuntimeException("redis down"));

        assertThatCode(scheduler::catchUpMissedDailyReport).doesNotThrowAnyException();
    }

    // ---------------- wiring ----------------

    @Test
    void firesAtMoldovaMidnight_andCatchUpRunsOnStartup() throws Exception {
        Scheduled scheduled =
                TelegramScheduler.class.getMethod("sendDailyReport").getAnnotation(Scheduled.class);
        String defaultZone = new TelegramBotProperties().getProfit().getZone();

        // Drift here would split the send time from the counters' day boundary.
        assertThat(scheduled.zone()).isEqualTo("${app.telegram.profit.zone:" + defaultZone + "}");

        ZoneId moldova = ZoneId.of(defaultZone);
        ZonedDateTime lateEvening = ZonedDateTime.of(2026, 9, 21, 23, 59, 0, 0, moldova);
        assertThat(CronExpression.parse(scheduled.cron()).next(lateEvening))
                .isEqualTo(ZonedDateTime.of(2026, 9, 22, 0, 0, 0, 0, moldova));

        EventListener onStartup =
                TelegramScheduler.class
                        .getMethod("catchUpMissedDailyReport")
                        .getAnnotation(EventListener.class);
        assertThat(onStartup.value()).containsExactly(ApplicationReadyEvent.class);
    }
}

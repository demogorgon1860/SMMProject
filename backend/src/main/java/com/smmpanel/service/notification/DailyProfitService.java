package com.smmpanel.service.notification;

import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.entity.DailyProfitSummary;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.DailyProfitSummaryRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Daily profit counters (Redis) and the end-of-day report.
 *
 * <p>A profit "day" is a calendar day in the business zone ({@code app.telegram.profit.zone},
 * Moldova by default), NOT the JVM zone — prod containers run in UTC. Counters are bucketed by that
 * local date and {@code TelegramScheduler} sends the report at that zone's midnight for the day
 * that just ended, so the report's day boundaries and its send time always agree (DST included).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyProfitService {

    private static final String PROFIT_KEY_PREFIX = "telegram:profit:";
    private static final String FIELD_TOTAL = "total";
    private static final String FIELD_COMPLETED = "completed_count";
    private static final String FIELD_PARTIAL = "partial_count";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final StringRedisTemplate stringRedisTemplate;
    private final DailyProfitSummaryRepository dailyProfitSummaryRepository;
    private final TelegramBotProperties telegramBotProperties;

    /** Wall clock; package-private setter so tests can pin "now" around the day boundary. */
    private Clock clock = Clock.systemUTC();

    private ZoneId businessZone;

    @PostConstruct
    void init() {
        // Fail fast on a typo'd zone rather than silently bucketing profit by the wrong day.
        businessZone = ZoneId.of(telegramBotProperties.getProfit().getZone());
        log.info("Daily profit day boundary: midnight {}", businessZone);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public void recordProfit(BigDecimal amount, OrderStatus status) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String key = keyFor(currentBusinessDay());
        long ttlDays = telegramBotProperties.getProfit().getRedisTtlDays();

        stringRedisTemplate.opsForHash().increment(key, FIELD_TOTAL, amount.doubleValue());
        if (status == OrderStatus.COMPLETED) {
            stringRedisTemplate.opsForHash().increment(key, FIELD_COMPLETED, 1);
        } else if (status == OrderStatus.PARTIAL) {
            stringRedisTemplate.opsForHash().increment(key, FIELD_PARTIAL, 1);
        }
        stringRedisTemplate.expire(key, ttlDays, TimeUnit.DAYS);
    }

    /** The calendar day, in the business zone, that profit recorded right now is counted into. */
    public LocalDate currentBusinessDay() {
        return LocalDate.ofInstant(clock.instant(), businessZone);
    }

    /** The most recently ended business day ("yesterday" in the business zone). */
    public LocalDate lastClosedBusinessDay() {
        return currentBusinessDay().minusDays(1);
    }

    /**
     * The day the midnight report run is closing. Read 12h back rather than at "now" so the answer
     * can't flip at the boundary: Spring arms the cron as a relative delay, so a wall-clock step
     * back (NTP / WSL2 time sync) can start the run a moment BEFORE local midnight — "now minus one
     * day" would then close the day before yesterday. Anything from 12h early to 12h late still
     * lands on the right day.
     */
    public LocalDate dayClosedByMidnightRun() {
        return LocalDate.ofInstant(clock.instant().minus(Duration.ofHours(12)), businessZone);
    }

    public boolean hasPersistedReport(LocalDate day) {
        return dailyProfitSummaryRepository.findByReportDate(day).isPresent();
    }

    public boolean hasCounters(LocalDate day) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(keyFor(day)));
    }

    public String buildDailyReportText(LocalDate day) {
        String key = keyFor(day);
        BigDecimal profit = getProfit(key);
        long completed = getLongField(key, FIELD_COMPLETED);
        long partial = getLongField(key, FIELD_PARTIAL);
        return String.format(
                "💰 Сутки завершены! (%s)%nВыполнено: %d (полных: %d, частичных: %d)%nПрофит: $%s",
                day.format(DATE_FMT),
                completed + partial,
                completed,
                partial,
                profit.toPlainString());
    }

    public void persistDailyReport(LocalDate day) {
        String key = keyFor(day);
        BigDecimal profit = getProfit(key);
        long completed = getLongField(key, FIELD_COMPLETED);
        long partial = getLongField(key, FIELD_PARTIAL);

        DailyProfitSummary summary =
                dailyProfitSummaryRepository
                        .findByReportDate(day)
                        .orElse(DailyProfitSummary.builder().reportDate(day).build());

        summary.setTotalProfit(profit);
        summary.setCompletedCount((int) completed);
        summary.setPartialCount((int) partial);
        dailyProfitSummaryRepository.save(summary);
        log.info(
                "Daily profit persisted: date={}, profit={}, completed={}, partial={}",
                day,
                profit,
                completed,
                partial);
    }

    private static String keyFor(LocalDate day) {
        return PROFIT_KEY_PREFIX + day;
    }

    private BigDecimal getProfit(String key) {
        Object val = stringRedisTemplate.opsForHash().get(key, FIELD_TOTAL);
        if (val == null) return BigDecimal.ZERO;
        try {
            // HINCRBYFLOAT accumulates in double; round to cents so the report and the persisted
            // daily_profit_summary carry an exact 2-decimal money value instead of float drift.
            return new BigDecimal(val.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private long getLongField(String key, String field) {
        Object val = stringRedisTemplate.opsForHash().get(key, field);
        if (val == null) return 0L;
        try {
            return Long.parseLong(val.toString().split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

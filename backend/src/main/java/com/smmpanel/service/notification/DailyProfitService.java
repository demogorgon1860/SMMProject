package com.smmpanel.service.notification;

import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.entity.DailyProfitSummary;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.DailyProfitSummaryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    public void recordProfit(BigDecimal amount, OrderStatus status) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String key = todayKey();
        long ttlDays = telegramBotProperties.getProfit().getRedisTtlDays();

        stringRedisTemplate.opsForHash().increment(key, FIELD_TOTAL, amount.doubleValue());
        if (status == OrderStatus.COMPLETED) {
            stringRedisTemplate.opsForHash().increment(key, FIELD_COMPLETED, 1);
        } else if (status == OrderStatus.PARTIAL) {
            stringRedisTemplate.opsForHash().increment(key, FIELD_PARTIAL, 1);
        }
        stringRedisTemplate.expire(key, ttlDays, TimeUnit.DAYS);
    }

    public BigDecimal getTodayProfit() {
        Object val = stringRedisTemplate.opsForHash().get(todayKey(), FIELD_TOTAL);
        if (val == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(val.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public long getTodayCompletedCount() {
        return getLongField(todayKey(), FIELD_COMPLETED);
    }

    public long getTodayPartialCount() {
        return getLongField(todayKey(), FIELD_PARTIAL);
    }

    public String buildDailyReportText() {
        BigDecimal profit = getTodayProfit();
        long completed = getTodayCompletedCount();
        long partial = getTodayPartialCount();
        String date = LocalDate.now().format(DATE_FMT);
        return String.format(
                "💰 Сутки завершены! (%s)%nВыполнено: %d (полных: %d, частичных: %d)%nПрофит: $%s",
                date, completed + partial, completed, partial, profit.toPlainString());
    }

    public void persistDailyReport() {
        LocalDate today = LocalDate.now();
        BigDecimal profit = getTodayProfit();
        long completed = getTodayCompletedCount();
        long partial = getTodayPartialCount();

        DailyProfitSummary summary =
                dailyProfitSummaryRepository
                        .findByReportDate(today)
                        .orElse(DailyProfitSummary.builder().reportDate(today).build());

        summary.setTotalProfit(profit);
        summary.setCompletedCount((int) completed);
        summary.setPartialCount((int) partial);
        dailyProfitSummaryRepository.save(summary);
        log.info(
                "Daily profit persisted: date={}, profit={}, completed={}, partial={}",
                today,
                profit,
                completed,
                partial);
    }

    private String todayKey() {
        return PROFIT_KEY_PREFIX + LocalDate.now();
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

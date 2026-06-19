package com.smmpanel.scheduler;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.client.InstagramBotClient.InstanceStatus;
import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.dto.instagram.ProfileErrorStat;
import com.smmpanel.service.notification.TelegramBotService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes infrastructure alerts to the "System Health" Telegram group:
 *
 * <ol>
 *   <li><b>Bot DOWN / recovered</b> — polls every bot instance's fast health probe and
 *       edge-triggers a 🔴 alert on a confirmed DOWN (after {@code downThreshold} consecutive
 *       failed polls, to absorb deploy/graceful-shutdown blips) and a 🟢 alert on recovery.
 *   <li><b>Worst-error profiles</b> — a cron digest of the AdsPower profiles producing the most
 *       action failures, aggregated by the bot from {@code order_results}.
 * </ol>
 *
 * <p>Both jobs are read-only (no {@code @Transactional}) and share the size-4 {@code scheduled-}
 * pool. Per-instance DOWN/UP state is persisted in Redis so a panel restart does not re-page an
 * already-down bot. All sends go through {@link TelegramBotService#sendToHealthChat} (which no-ops
 * when the health channel is not configured), keeping infra alerts off the main order channel.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemHealthMonitor {

    private static final String STATE_PREFIX = "telegram:health:bot:";
    private static final String FIELD_STATE = "state";
    private static final String FIELD_FAIL_STREAK = "fail_streak";
    private static final String FIELD_DOWN_SINCE = "down_since";
    private static final String STATE_UP = "UP";
    private static final String STATE_DOWN = "DOWN";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final TelegramBotProperties props;
    private final TelegramBotService telegramBotService;
    private final InstagramBotClient instagramBotClient;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Poll bot liveness on a fixed delay. Always scheduled; no-ops when the System Health channel
     * is not operational (disabled / no chat-id / no token).
     */
    @Scheduled(
            fixedDelayString = "${app.telegram.health.check-interval-ms:60000}",
            initialDelayString = "${app.telegram.health.check-interval-ms:60000}")
    public void pollBotLiveness() {
        if (!telegramBotService.isHealthChannelOperational()) return;
        try {
            List<InstanceStatus> statuses = instagramBotClient.getAllInstanceStatuses();
            for (InstanceStatus st : statuses) {
                evaluateInstance(st);
            }
        } catch (Exception e) {
            log.warn("System Health bot-liveness poll failed: {}", e.getMessage());
        }
    }

    /**
     * Send the worst-error-profiles digest on a cron. Skips the send entirely when nothing
     * qualifies.
     */
    @Scheduled(cron = "${app.telegram.health.profiles.report-cron:0 0 9 * * *}")
    public void reportWorstProfiles() {
        if (!telegramBotService.isHealthChannelOperational()) return;
        TelegramBotProperties.Health.Profiles cfg = props.getHealth().getProfiles();
        try {
            List<ProfileErrorStat> stats =
                    instagramBotClient.getProfileErrorStats(
                            cfg.getWindowHours(), cfg.getMinErrors(), cfg.getTopN());
            if (stats == null || stats.isEmpty()) {
                log.info(
                        "System Health: no problem profiles in last {}h (>= {} errors) — skipping"
                                + " digest",
                        cfg.getWindowHours(),
                        cfg.getMinErrors());
                return;
            }
            telegramBotService.sendToHealthChat(
                    formatProfilesDigest(stats, cfg.getWindowHours(), cfg.getMinErrors()));
            log.info("System Health: worst-profiles digest sent ({} profiles)", stats.size());
        } catch (Exception e) {
            log.warn("System Health worst-profiles digest failed: {}", e.getMessage());
        }
    }

    // ===================== Bot-down state machine =====================

    private void evaluateInstance(InstanceStatus st) {
        String key = STATE_PREFIX + slug(st.getBaseUrl());
        HashOperations<String, Object, Object> hash = stringRedisTemplate.opsForHash();

        // Prior state is read from Redis (persisted across restarts) so an already-DOWN bot is not
        // re-alerted after a panel restart.
        String prevState = asStr(hash.get(key, FIELD_STATE));
        int failStreak = parseIntOr(asStr(hash.get(key, FIELD_FAIL_STREAK)), 0);

        if (st.isOnline()) {
            if (STATE_DOWN.equals(prevState)) {
                sendRecovery(st, asStr(hash.get(key, FIELD_DOWN_SINCE)));
            }
            hash.put(key, FIELD_STATE, STATE_UP);
            hash.put(key, FIELD_FAIL_STREAK, "0");
            hash.delete(key, FIELD_DOWN_SINCE);
        } else {
            failStreak++;
            hash.put(key, FIELD_FAIL_STREAK, String.valueOf(failStreak));
            if (!STATE_DOWN.equals(prevState) && failStreak >= downThreshold()) {
                hash.put(key, FIELD_STATE, STATE_DOWN);
                hash.put(key, FIELD_DOWN_SINCE, Instant.now().toString());
                sendDown(st);
            }
        }

        // Self-clean keys for instances that get removed from config later.
        stringRedisTemplate.expire(key, Duration.ofDays(30));
    }

    private void sendDown(InstanceStatus st) {
        String reason =
                (st.getLastError() != null && !st.getLastError().isBlank())
                        ? st.getLastError()
                        : "не отвечает";
        String text =
                String.format(
                        "🔴 БОТ НЕДОСТУПЕН%nИнстанс: %s%nПричина: %s%nВремя: %s",
                        st.getBaseUrl(), reason, TS.format(Instant.now()));
        telegramBotService.sendToHealthChat(text);
        log.warn("System Health: bot instance {} is DOWN ({})", st.getBaseUrl(), reason);
    }

    private void sendRecovery(InstanceStatus st, String downSinceIso) {
        String text =
                String.format(
                        "🟢 Бот восстановлен%nИнстанс: %s%nБыл недоступен: %s",
                        st.getBaseUrl(), humanizeDowntime(downSinceIso));
        telegramBotService.sendToHealthChat(text);
        log.info("System Health: bot instance {} recovered", st.getBaseUrl());
    }

    // ===================== Formatting / helpers =====================

    private String formatProfilesDigest(
            List<ProfileErrorStat> stats, int windowHours, int minErrors) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                String.format("⚠️ Топ профилей по ошибкам (за %dч, ≥%d)", windowHours, minErrors));
        int rank = 1;
        for (ProfileErrorStat s : stats) {
            sb.append(
                    String.format(
                            "%n%d. %s — %d ошиб. (%d fail / %d profile_failed) из %d действий",
                            rank++,
                            s.getProfileAdsPowerId(),
                            s.errorCount(),
                            s.getFailed(),
                            s.getProfileFailed(),
                            s.getTotalActions()));
        }
        return sb.toString();
    }

    private int downThreshold() {
        return Math.max(1, props.getHealth().getDownThreshold());
    }

    /** Stable, Redis-key-safe slug for a bot URL (strips scheme, non-alphanumerics → '_'). */
    private static String slug(String url) {
        if (url == null || url.isBlank()) return "unknown";
        return url.replaceAll("^https?://", "").replaceAll("[^a-zA-Z0-9]", "_");
    }

    private static String humanizeDowntime(String downSinceIso) {
        if (downSinceIso == null || downSinceIso.isBlank()) return "неизвестно";
        try {
            Duration d = Duration.between(Instant.parse(downSinceIso), Instant.now());
            long minutes = Math.max(0, d.toMinutes());
            if (minutes < 60) return minutes + " мин";
            long hours = minutes / 60;
            long rem = minutes % 60;
            return rem == 0 ? hours + " ч" : hours + " ч " + rem + " мин";
        } catch (Exception e) {
            return "неизвестно";
        }
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

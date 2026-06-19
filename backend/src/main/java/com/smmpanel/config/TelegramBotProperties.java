package com.smmpanel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.telegram")
public class TelegramBotProperties {

    private boolean enabled = true;

    private Bot bot = new Bot();
    private Proxy proxy = new Proxy();
    private Cancel cancel = new Cancel();
    private Profit profit = new Profit();
    private Health health = new Health();

    @Data
    public static class Bot {
        private String token;
        private String chatId;
        private String webhookSecret = "";
    }

    @Data
    public static class Proxy {
        private boolean enabled = false;
        private String host;
        private int port;
        private String username;
        private String password;
    }

    @Data
    public static class Cancel {
        private int timeoutHours = 4;
        private String defaultAction = "proceed"; // proceed | cancel
    }

    @Data
    public static class Profit {
        private int redisTtlDays = 8;
    }

    /**
     * "System Health" group — a SEPARATE Telegram chat from the main order channel that receives
     * infrastructure alerts: bot-DOWN events and a periodic worst-error-profile digest. Reuses the
     * same bot token (the bot must be a member of both groups). When {@code chatId} is blank,
     * health alerts are DISABLED (we deliberately do NOT fall back to {@code bot.chatId} — the
     * whole point is to keep infra noise out of the operator's order channel).
     */
    @Data
    public static class Health {
        private boolean enabled = true;
        private String chatId = "";

        /** Bot-liveness poll interval (ms). Also drives the scheduler's fixedDelay. */
        private long checkIntervalMs = 60000;

        /**
         * Consecutive failed polls before a DOWN alert fires. Debounces deploy/restart flaps — the
         * bot does up to 90s graceful shutdown, so a single transient failure must not page.
         */
        private int downThreshold = 2;

        private Profiles profiles = new Profiles();

        @Data
        public static class Profiles {
            /** Cron for the worst-profiles digest (default daily 09:00). */
            private String reportCron = "0 0 9 * * *";

            /** Aggregation window (hours) for the leaderboard. */
            private int windowHours = 24;

            /**
             * Minimum profile-fault rows for a profile to be listed ("failed after N attempts").
             */
            private int minErrors = 5;

            /** Max profiles in the digest. */
            private int topN = 10;
        }
    }
}

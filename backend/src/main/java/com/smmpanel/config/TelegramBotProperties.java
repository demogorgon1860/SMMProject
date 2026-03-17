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
    private Cancel cancel = new Cancel();
    private Profit profit = new Profit();

    @Data
    public static class Bot {
        private String token;
        private String chatId;
        private String webhookSecret = "";
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
}

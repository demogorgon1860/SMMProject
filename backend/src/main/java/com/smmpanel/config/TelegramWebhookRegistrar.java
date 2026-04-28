package com.smmpanel.config;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class TelegramWebhookRegistrar implements ApplicationRunner {

    private static final String SET_WEBHOOK_URL = "https://api.telegram.org/bot%s/setWebhook";
    private static final String WEBHOOK_PATH = "/api/telegram/webhook";

    private final TelegramBotProperties props;
    private final RestTemplate restTemplate;

    public TelegramWebhookRegistrar(
            TelegramBotProperties props,
            @Qualifier("telegramRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.restTemplate = restTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            log.info("Telegram notifications disabled — skipping webhook registration");
            return;
        }
        if (!StringUtils.hasText(props.getBot().getToken())
                || !StringUtils.hasText(props.getBot().getChatId())) {
            log.warn("============================================================");
            log.warn("TELEGRAM NOTIFICATIONS ENABLED BUT TOKEN/CHAT_ID MISSING —");
            log.warn("set TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT_ID in .env.docker, or");
            log.warn("set TELEGRAM_NOTIFICATIONS_ENABLED=false to silence this warning.");
            log.warn("============================================================");
            return;
        }
        try {
            String url = String.format(SET_WEBHOOK_URL, props.getBot().getToken());
            String webhookUrl = "https://smmworld.vip" + WEBHOOK_PATH;

            Map<String, Object> body;
            String secret = props.getBot().getWebhookSecret();
            if (StringUtils.hasText(secret)) {
                body = Map.of("url", webhookUrl, "secret_token", secret);
            } else {
                body = Map.of("url", webhookUrl);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> response =
                    restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                log.info("Telegram webhook registered: {}", webhookUrl);
            } else {
                log.warn("Telegram webhook registration returned: {}", response);
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to register Telegram webhook (app will still start): {}",
                    e.getMessage());
        }
    }
}

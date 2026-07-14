package com.smmpanel.controller;

import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.dto.telegram.TelegramUpdate;
import com.smmpanel.service.notification.TelegramUpdateHandler;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/telegram")
@RequiredArgsConstructor
public class TelegramWebhookController {

    private final TelegramUpdateHandler telegramUpdateHandler;
    private final TelegramBotProperties telegramBotProperties;

    @PostConstruct
    public void warnIfNoSecret() {
        if (!StringUtils.hasText(telegramBotProperties.getBot().getWebhookSecret())) {
            log.warn(
                    "⚠️  TELEGRAM_WEBHOOK_SECRET is empty — /api/telegram/webhook accepts"
                            + " unauthenticated callbacks. Set TELEGRAM_WEBHOOK_SECRET in .env and"
                            + " re-register the webhook with setWebhook?secret_token=<value>.");
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleUpdate(
            @RequestBody TelegramUpdate update,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false)
                    String secret) {

        String configuredSecret = telegramBotProperties.getBot().getWebhookSecret();

        // Fail closed: without a configured secret we cannot authenticate the caller, and an
        // unauthenticated callback_query can trigger a real refund (cancel_do:{orderId}). Reject
        // rather than trust. Operators MUST set TELEGRAM_WEBHOOK_SECRET and re-register the
        // webhook.
        if (!StringUtils.hasText(configuredSecret)) {
            log.error(
                    "Rejecting Telegram webhook: TELEGRAM_WEBHOOK_SECRET is not configured — set it"
                            + " and re-register with setWebhook?secret_token=<value>");
            return ResponseEntity.status(401).build();
        }

        // Constant-time comparison to avoid leaking the secret via response timing.
        byte[] expected = configuredSecret.getBytes(StandardCharsets.UTF_8);
        byte[] provided = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            log.warn("Invalid Telegram webhook secret token");
            return ResponseEntity.status(401).build();
        }

        telegramUpdateHandler.process(update);
        return ResponseEntity.ok().build();
    }
}

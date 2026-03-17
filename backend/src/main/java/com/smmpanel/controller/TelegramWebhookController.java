package com.smmpanel.controller;

import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.dto.telegram.TelegramUpdate;
import com.smmpanel.service.notification.TelegramUpdateHandler;
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

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleUpdate(
            @RequestBody TelegramUpdate update,
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false)
                    String secret) {

        String configuredSecret = telegramBotProperties.getBot().getWebhookSecret();
        if (StringUtils.hasText(configuredSecret) && !configuredSecret.equals(secret)) {
            log.warn("Invalid Telegram webhook secret token");
            return ResponseEntity.status(401).build();
        }

        telegramUpdateHandler.process(update);
        return ResponseEntity.ok().build();
    }
}

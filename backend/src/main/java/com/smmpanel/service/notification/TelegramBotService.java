package com.smmpanel.service.notification;

import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.entity.Order;
import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Core Telegram Bot API service. Sends messages directly to Telegram API without external
 * intermediaries (replaces n8n webhook forwarding).
 */
@Slf4j
@Service
public class TelegramBotService {

    private static final String API_BASE = "https://api.telegram.org/bot";

    private final TelegramBotProperties props;
    private final CancelDecisionService cancelDecisionService;
    private final RestTemplate restTemplate;

    public TelegramBotService(
            TelegramBotProperties props,
            CancelDecisionService cancelDecisionService,
            @Qualifier("telegramRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.cancelDecisionService = cancelDecisionService;
        this.restTemplate = restTemplate;
    }

    /**
     * Loud startup banner when Telegram delivery is disabled or misconfigured. Same pattern as
     * {@code EmailService.announceConfiguration()} (commit 874acb29) — without this the bot's
     * circuit-breaker fires, the panel marks the order paused, and no Telegram message is sent. The
     * admin never sees the prompt, the decision times out, and the default action applies. A single
     * startup banner makes the misconfiguration impossible to miss in {@code docker-compose logs}.
     */
    @PostConstruct
    void announceConfiguration() {
        if (!props.isEnabled()) {
            log.warn("============================================================");
            log.warn("TELEGRAM NOTIFICATIONS DISABLED — set app.telegram.enabled=true");
            log.warn("(via TELEGRAM_ENABLED) plus TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID");
            log.warn("to enable circuit-breaker / completion alerts. Until then, all");
            log.warn("Telegram sends are silently skipped.");
            log.warn("============================================================");
            return;
        }
        if (isMissingCredentials()) {
            log.error("============================================================");
            log.error("TELEGRAM ENABLED BUT BOT TOKEN OR CHAT ID IS BLANK — every");
            log.error("notification will silently no-op. Set TELEGRAM_BOT_TOKEN and");
            log.error("TELEGRAM_CHAT_ID in .env.docker, or set TELEGRAM_ENABLED=false");
            log.error("to silence this warning.");
            log.error("============================================================");
            return;
        }
        log.info(
                "Telegram notifications active (chat_id={}, cancel-timeout={}h,"
                        + " default-action={})",
                props.getBot().getChatId(),
                props.getCancel().getTimeoutHours(),
                props.getCancel().getDefaultAction());
    }

    /**
     * True when Telegram is enabled <em>and</em> the bot token / chat id are populated. Send paths
     * use this instead of {@code props.isEnabled()} alone so a misconfigured prod instance fails at
     * the no-op gate rather than firing malformed HTTP calls at api.telegram.org.
     */
    private boolean isOperational() {
        return props.isEnabled() && !isMissingCredentials();
    }

    /** True when {@code app.telegram.enabled=true} but the bot token or chat id is blank. */
    private boolean isMissingCredentials() {
        TelegramBotProperties.Bot bot = props.getBot();
        if (bot == null) return true;
        return bot.getToken() == null
                || bot.getToken().isBlank()
                || bot.getChatId() == null
                || bot.getChatId().isBlank();
    }

    // ===================== Notification methods =====================

    @Async("asyncExecutor")
    public void notifyNewOrder(Order order) {
        if (!isOperational()) return;
        String text =
                String.format(
                        "⛏️ Новый заказ #%d\nУслуга: %s\nКоличество: %d | Цена: $%s",
                        order.getId(),
                        serviceName(order),
                        order.getQuantity(),
                        order.getCharge().toPlainString());
        sendMessage(text);
    }

    @Async("asyncExecutor")
    public void notifyOrderCompleted(Order order, Integer completed) {
        if (!isOperational()) return;
        String text =
                String.format(
                        "✅ Заказ #%d выполнен!\nУслуга: %s\nВыполнено: %d | Прибыль: $%s",
                        order.getId(),
                        serviceName(order),
                        completed != null ? completed : order.getQuantity(),
                        order.getCharge().toPlainString());
        sendMessage(text);
    }

    @Async("asyncExecutor")
    public void notifyOrderPartial(Order order, Integer completed) {
        if (!isOperational()) return;
        String text =
                String.format(
                        "⚠️ Заказ #%d частично выполнен\n"
                                + "Услуга: %s\n"
                                + "Заказано: %d | Выполнено: %d\n"
                                + "Прибыль (факт): $%s",
                        order.getId(),
                        serviceName(order),
                        order.getQuantity(),
                        completed != null ? completed : 0,
                        order.getCharge().toPlainString());
        sendMessage(text);
    }

    @Async("asyncExecutor")
    public void notifyOrderFailed(Order order, Integer completed) {
        if (!isOperational()) return;
        String text =
                String.format(
                        "❌ Заказ #%d не выполнен\nУслуга: %s\nРефанд: $%s",
                        order.getId(), serviceName(order), order.getCharge().toPlainString());
        sendMessage(text);
    }

    @Async("asyncExecutor")
    public void notifyOrderCancelledPending(Order order, Integer completedCount) {
        if (!isOperational()) return;

        // Guard against duplicate notifications from concurrent RabbitMQ + webhook paths.
        // Check before sending to avoid sending a Telegram message we can't track.
        if (cancelDecisionService.getPendingDecision(order.getId()).isPresent()) {
            log.info(
                    "Cancel pending decision already exists for order {} — skipping duplicate"
                            + " notification",
                    order.getId());
            return;
        }

        String progress =
                (completedCount != null)
                        ? String.format("Выполнено: %d из %d", completedCount, order.getQuantity())
                        : String.format("Количество: %d", order.getQuantity());

        String text =
                String.format(
                        "⚠️ Бот остановил заказ #%d\n"
                                + "Услуга: %s\n"
                                + "%s\n\n"
                                + "Что делать с заказом?",
                        order.getId(), serviceName(order), progress);

        Integer messageId = sendMessageWithInlineKeyboard(text, order.getId());

        CancelPendingDecision decision =
                CancelPendingDecision.builder()
                        .orderId(order.getId())
                        .botOrderId(order.getInstagramBotOrderId())
                        .telegramMessageId(messageId)
                        .createdAt(LocalDateTime.now())
                        .orderStatusAtTime(order.getStatus().name())
                        .completedCount(completedCount)
                        .originalCount(order.getQuantity())
                        .build();

        boolean stored = cancelDecisionService.storePendingDecision(order.getId(), decision);
        if (!stored) {
            // Narrow race between check and store — another thread got there first.
            // Log only; the other thread's message is already in Telegram.
            log.warn(
                    "Cancel pending decision race for order {} — duplicate Telegram message may"
                            + " have been sent",
                    order.getId());
        }
    }

    // ===================== Edit message (remove buttons on expiry) =====================

    public void removeInlineKeyboard(Integer messageId) {
        if (messageId == null) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", props.getBot().getChatId());
            body.put("message_id", messageId);
            body.put("reply_markup", Map.of("inline_keyboard", List.of()));
            post("editMessageReplyMarkup", body);
        } catch (Exception e) {
            log.warn(
                    "Failed to remove inline keyboard from message {}: {}",
                    messageId,
                    e.getMessage());
        }
    }

    /**
     * Replace both the text and inline keyboard of a previously sent message in one API call. Used
     * to show progressive status ("обрабатывается..." → "возобновлён") to the admin and
     * simultaneously strip the buttons so the decision can't be clicked twice.
     */
    public void editMessageText(Integer messageId, String text) {
        if (messageId == null) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", props.getBot().getChatId());
            body.put("message_id", messageId);
            body.put("text", text);
            body.put("reply_markup", Map.of("inline_keyboard", List.of()));
            post("editMessageText", body);
        } catch (Exception e) {
            // Telegram returns 400 "message is not modified" on identical re-edits — not an error.
            log.debug("Failed to edit message {}: {}", messageId, e.getMessage());
        }
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("callback_query_id", callbackQueryId);
            body.put("text", text);
            post("answerCallbackQuery", body);
        } catch (Exception e) {
            log.warn("Failed to answer callback query {}: {}", callbackQueryId, e.getMessage());
        }
    }

    public void sendPlainMessage(String text) {
        sendMessage(text);
    }

    // ===================== System Health channel (separate group) =====================

    /**
     * True when System Health alerts can be delivered: Telegram is enabled, the health channel is
     * enabled, the bot token is present, and a System Health chat id is configured. Deliberately
     * does NOT depend on {@link #isMissingCredentials()} (the MAIN order chat-id) — health alerts
     * go to a different audience and must still fire even if the main chat is misconfigured.
     */
    public boolean isHealthChannelOperational() {
        TelegramBotProperties.Bot bot = props.getBot();
        TelegramBotProperties.Health health = props.getHealth();
        return props.isEnabled()
                && health != null
                && health.isEnabled()
                && bot != null
                && bot.getToken() != null
                && !bot.getToken().isBlank()
                && health.getChatId() != null
                && !health.getChatId().isBlank();
    }

    /**
     * Send a plain-text message to the System Health group. No-ops silently when the health channel
     * is not operational. Reuses the same Telegram RestTemplate; errors are logged only, never
     * propagated (callers run inside scheduler threads and must not be aborted by a failed send).
     */
    public void sendToHealthChat(String text) {
        if (!isHealthChannelOperational()) return;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", props.getHealth().getChatId());
            body.put("text", text);
            postRaw("sendMessage", body);
        } catch (Exception e) {
            log.error("Failed to send System Health Telegram message: {}", e.getMessage());
        }
    }

    // ===================== Internal helpers =====================

    private void sendMessage(String text) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", props.getBot().getChatId());
            body.put("text", text);
            post("sendMessage", body);
        } catch (Exception e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
        }
    }

    private Integer sendMessageWithInlineKeyboard(String text, Long orderId) {
        try {
            Map<String, Object> proceedBtn =
                    Map.of("text", "✅ Продолжить", "callback_data", "cancel_proceed:" + orderId);
            Map<String, Object> cancelBtn =
                    Map.of("text", "❌ Отменить", "callback_data", "cancel_do:" + orderId);
            Map<String, Object> replyMarkup =
                    Map.of("inline_keyboard", List.of(List.of(proceedBtn, cancelBtn)));

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", props.getBot().getChatId());
            body.put("text", text);
            body.put("reply_markup", replyMarkup);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = post("sendMessage", body);
            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                if (result != null) {
                    return (Integer) result.get("message_id");
                }
            }
        } catch (Exception e) {
            log.error(
                    "Failed to send Telegram inline keyboard message for order {}: {}",
                    orderId,
                    e.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String method, Map<String, Object> body) {
        // Centralized "no creds → no HTTP call" gate. Protects internal callers (scheduler, update
        // handler) that don't go through the @Async notify* methods and would otherwise fire a
        // request at https://api.telegram.org/bot/method (token blank → 401 spam in logs).
        if (!isOperational()) return null;
        String url = API_BASE + props.getBot().getToken() + "/" + method;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(url, request, Map.class);
    }

    /**
     * Like {@link #post} but gated ONLY on Telegram being enabled + a token being present — NOT on
     * the main chat-id. The caller supplies {@code chat_id} in {@code body}. Used by the System
     * Health path so a blank main chat-id doesn't suppress infra alerts (different chat, different
     * failure mode). Reuses the same {@code telegramRestTemplate}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postRaw(String method, Map<String, Object> body) {
        if (!props.isEnabled()) return null;
        TelegramBotProperties.Bot bot = props.getBot();
        if (bot == null || bot.getToken() == null || bot.getToken().isBlank()) return null;
        String url = API_BASE + bot.getToken() + "/" + method;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(url, request, Map.class);
    }

    private String serviceName(Order order) {
        return order.getService() != null ? order.getService().getName() : "Unknown";
    }
}

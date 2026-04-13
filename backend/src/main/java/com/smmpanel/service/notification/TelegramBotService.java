package com.smmpanel.service.notification;

import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
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
    private final DailyProfitService dailyProfitService;
    private final RestTemplate restTemplate;

    public TelegramBotService(
            TelegramBotProperties props,
            CancelDecisionService cancelDecisionService,
            DailyProfitService dailyProfitService,
            @Qualifier("telegramRestTemplate") RestTemplate restTemplate) {
        this.props = props;
        this.cancelDecisionService = cancelDecisionService;
        this.dailyProfitService = dailyProfitService;
        this.restTemplate = restTemplate;
    }

    // ===================== Notification methods =====================

    @Async("asyncExecutor")
    public void notifyNewOrder(Order order) {
        if (!props.isEnabled()) return;
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
        if (!props.isEnabled()) return;
        String text =
                String.format(
                        "✅ Заказ #%d выполнен!\nУслуга: %s\nВыполнено: %d | Прибыль: $%s",
                        order.getId(),
                        serviceName(order),
                        completed != null ? completed : order.getQuantity(),
                        order.getCharge().toPlainString());
        sendMessage(text);
        dailyProfitService.recordProfit(order.getCharge(), OrderStatus.COMPLETED);
    }

    @Async("asyncExecutor")
    public void notifyOrderPartial(Order order, Integer completed) {
        if (!props.isEnabled()) return;
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
        dailyProfitService.recordProfit(order.getCharge(), OrderStatus.PARTIAL);
    }

    @Async("asyncExecutor")
    public void notifyOrderFailed(Order order, Integer completed) {
        if (!props.isEnabled()) return;
        String text =
                String.format(
                        "❌ Заказ #%d не выполнен\nУслуга: %s\nРефанд: $%s",
                        order.getId(), serviceName(order), order.getCharge().toPlainString());
        sendMessage(text);
    }

    @Async("asyncExecutor")
    public void notifyOrderCancelledPending(Order order, Integer completedCount) {
        if (!props.isEnabled()) return;

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
        String url = API_BASE + props.getBot().getToken() + "/" + method;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(url, request, Map.class);
    }

    private String serviceName(Order order) {
        return order.getService() != null ? order.getService().getName() : "Unknown";
    }
}

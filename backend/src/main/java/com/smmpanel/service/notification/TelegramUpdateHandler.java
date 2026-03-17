package com.smmpanel.service.notification;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.dto.telegram.TelegramCallbackQuery;
import com.smmpanel.dto.telegram.TelegramUpdate;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramUpdateHandler {

    private static final String PROCEED = "cancel_proceed:";
    private static final String CANCEL = "cancel_do:";

    private final CancelDecisionService cancelDecisionService;
    private final TelegramBotService telegramBotService;
    private final OrderRepository orderRepository;
    private final BalanceService balanceService;
    private final InstagramBotClient instagramBotClient;

    @Async("asyncExecutor")
    public void process(TelegramUpdate update) {
        if (update.getCallbackQuery() != null) {
            handleCallbackQuery(update.getCallbackQuery());
        }
    }

    @Transactional
    protected void handleCallbackQuery(TelegramCallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        if (data == null) return;

        try {
            if (data.startsWith(PROCEED)) {
                Long orderId = Long.parseLong(data.substring(PROCEED.length()));
                handleProceed(
                        callbackQuery.getId(),
                        orderId,
                        callbackQuery.getMessage() != null
                                ? callbackQuery.getMessage().getMessageId()
                                : null);
            } else if (data.startsWith(CANCEL)) {
                Long orderId = Long.parseLong(data.substring(CANCEL.length()));
                handleCancel(
                        callbackQuery.getId(),
                        orderId,
                        callbackQuery.getMessage() != null
                                ? callbackQuery.getMessage().getMessageId()
                                : null);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid callback data: {}", data);
            telegramBotService.answerCallbackQuery(
                    callbackQuery.getId(), "Ошибка: неверный формат данных");
        }
    }

    private void handleProceed(String callbackQueryId, Long orderId, Integer messageId) {
        Optional<CancelPendingDecision> decisionOpt =
                cancelDecisionService.getPendingDecision(orderId);
        if (decisionOpt.isEmpty()) {
            telegramBotService.answerCallbackQuery(
                    callbackQueryId, "Решение уже принято или истекло");
            return;
        }

        CancelPendingDecision decision = decisionOpt.get();
        String botOrderId = decision.getBotOrderId();

        // Resume the paused order in the bot so it continues from where it stopped
        boolean resumed = false;
        if (botOrderId != null && !botOrderId.isBlank()) {
            resumed = instagramBotClient.resumeOrder(botOrderId);
            if (!resumed) {
                log.warn("Could not resume bot order {} for panel order {}", botOrderId, orderId);
            }
        }

        cancelDecisionService.removePendingDecision(orderId);
        telegramBotService.removeInlineKeyboard(messageId);
        telegramBotService.answerCallbackQuery(callbackQueryId, "✅ Заказ возобновлён");

        String msg =
                resumed
                        ? String.format(
                                "✅ Заказ #%d возобновлён — бот продолжает с места остановки",
                                orderId)
                        : String.format(
                                "⚠️ Заказ #%d: сигнал боту не прошёл (возможно уже завершён)",
                                orderId);
        telegramBotService.sendPlainMessage(msg);
        log.info("Admin chose PROCEED for order {} (bot resume: {})", orderId, resumed);
    }

    @Transactional
    protected void handleCancel(String callbackQueryId, Long orderId, Integer messageId) {
        Optional<CancelPendingDecision> decisionOpt =
                cancelDecisionService.getPendingDecision(orderId);
        if (decisionOpt.isEmpty()) {
            telegramBotService.answerCallbackQuery(
                    callbackQueryId, "Решение уже принято или истекло");
            return;
        }

        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("Order {} not found when processing cancel decision", orderId);
            telegramBotService.answerCallbackQuery(callbackQueryId, "Заказ не найден");
            cancelDecisionService.removePendingDecision(orderId);
            return;
        }

        Order order = orderOpt.get();

        // Tell the bot to cancel (it may be in paused state waiting for our decision)
        String botOrderId = decisionOpt.get().getBotOrderId();
        if (botOrderId != null && !botOrderId.isBlank()) {
            instagramBotClient.cancelOrder(botOrderId);
        }

        processFullRefund(order);
        order.setStatus(OrderStatus.CANCELLED);
        order.setTrafficStatus("CANCELLED_BY_ADMIN");
        orderRepository.save(order);

        cancelDecisionService.removePendingDecision(orderId);
        telegramBotService.removeInlineKeyboard(messageId);
        telegramBotService.answerCallbackQuery(callbackQueryId, "❌ Заказ отменён");
        telegramBotService.sendPlainMessage(
                String.format(
                        "❌ Заказ #%d отменён администратором. Рефанд: $%s",
                        orderId, order.getCharge().toPlainString()));
        log.info("Admin chose CANCEL for order {}", orderId);
    }

    private void processFullRefund(Order order) {
        if (order.getCharge() == null || order.getCharge().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        if (order.getUser() != null) {
            String reason =
                    String.format(
                            "Full refund for Instagram order #%d: cancelled by admin",
                            order.getId());
            balanceService.refund(order.getUser(), order.getCharge(), order, reason);
            log.info(
                    "Full refund {} for order {} (admin cancel)", order.getCharge(), order.getId());
            order.setCharge(BigDecimal.ZERO);
        }
    }
}

package com.smmpanel.service.notification;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.dto.telegram.TelegramCallbackQuery;
import com.smmpanel.dto.telegram.TelegramUpdate;
import com.smmpanel.entity.OrderStatus;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Processes Telegram webhook updates (callback_query from inline keyboard buttons).
 *
 * <p>Ordering guarantees to keep the Telegram loading spinner under ~15s:
 *
 * <ol>
 *   <li>answerCallbackQuery is always the FIRST call — closes the spinner immediately.
 *   <li>Idempotency lock on update_id (guards against Telegram retries when our response is slow).
 *   <li>Transactional DB work (refund + status) is delegated to {@link TelegramCallbackTxService} —
 *       a separate bean so @Transactional is applied through the AOP proxy.
 *   <li>Bot HTTP calls use the short-timeout fast-path to avoid 30s+ blocks.
 *   <li>Final status is pushed back to admin via editMessageText (same message, no new chat spam).
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramUpdateHandler {

    private static final String PROCEED = "cancel_proceed:";
    private static final String CANCEL = "cancel_do:";

    private final CancelDecisionService cancelDecisionService;
    private final TelegramBotService telegramBotService;
    private final InstagramBotClient instagramBotClient;
    private final TelegramCallbackTxService callbackTxService;
    private final DailyProfitService dailyProfitService;

    @Async("asyncExecutor")
    public void process(TelegramUpdate update) {
        try {
            if (update.getCallbackQuery() != null) {
                handleCallbackQuery(update.getUpdateId(), update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("Unhandled exception in Telegram update processing: {}", e.getMessage(), e);
            if (update.getCallbackQuery() != null) {
                try {
                    telegramBotService.answerCallbackQuery(
                            update.getCallbackQuery().getId(), "⚠️ Внутренняя ошибка");
                } catch (Exception ignored) {
                    // best-effort
                }
            }
        }
    }

    private void handleCallbackQuery(Long updateId, TelegramCallbackQuery cq) {
        String data = cq.getData();
        if (data == null) return;

        String callbackId = cq.getId();
        Integer messageId = cq.getMessage() != null ? cq.getMessage().getMessageId() : null;

        // STEP 1 — close the Telegram loading spinner immediately (<200ms).
        telegramBotService.answerCallbackQuery(callbackId, "⏳ Обрабатывается...");

        // STEP 2 — idempotency lock against Telegram retries of the same update_id.
        if (!cancelDecisionService.acquireCallbackLock(updateId)) {
            log.info("Duplicate callback update_id={} — skipping", updateId);
            return;
        }

        try {
            if (data.startsWith(PROCEED)) {
                handleProceed(Long.parseLong(data.substring(PROCEED.length())), messageId);
            } else if (data.startsWith(CANCEL)) {
                handleCancel(Long.parseLong(data.substring(CANCEL.length())), messageId);
            } else {
                log.warn("Unknown callback data: {}", data);
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid callback data: {}", data);
            telegramBotService.editMessageText(
                    messageId, "⚠️ Ошибка: неверный формат callback_data");
        } catch (Exception e) {
            log.error("Callback handler failed for update_id={}: {}", updateId, e.getMessage(), e);
            telegramBotService.editMessageText(
                    messageId, "⚠️ Ошибка обработки. Проверьте логи SMM-панели.");
        }
    }

    private void handleProceed(Long orderId, Integer messageId) {
        Optional<CancelPendingDecision> decisionOpt =
                callbackTxService.readPendingDecision(orderId);
        if (decisionOpt.isEmpty()) {
            telegramBotService.editMessageText(
                    messageId,
                    String.format("ℹ️ Заказ #%d: решение уже принято или истекло", orderId));
            return;
        }

        // Remove decision first so a stray second click or scheduler tick sees "already decided".
        callbackTxService.removePendingDecision(orderId);
        telegramBotService.editMessageText(
                messageId, String.format("⏳ Заказ #%d: возобновляем в боте...", orderId));

        boolean resumed = false;
        String botOrderId = decisionOpt.get().getBotOrderId();
        if (botOrderId != null && !botOrderId.isBlank()) {
            try {
                resumed = instagramBotClient.resumeOrderFast(botOrderId);
            } catch (Exception e) {
                log.warn("resumeOrderFast failed for bot order {}: {}", botOrderId, e.getMessage());
            }
        }

        telegramBotService.editMessageText(
                messageId,
                resumed
                        ? String.format("✅ Заказ #%d возобновлён — бот продолжает", orderId)
                        : String.format(
                                "⚠️ Заказ #%d: сигнал боту не прошёл (возможно уже завершён)",
                                orderId));
        log.info("Admin PROCEED order={} resumed={}", orderId, resumed);
    }

    private void handleCancel(Long orderId, Integer messageId) {
        Optional<CancelPendingDecision> decisionOpt =
                callbackTxService.readPendingDecision(orderId);
        if (decisionOpt.isEmpty()) {
            telegramBotService.editMessageText(
                    messageId,
                    String.format("ℹ️ Заказ #%d: решение уже принято или истекло", orderId));
            return;
        }

        CancelPendingDecision decision = decisionOpt.get();
        Integer completedCount = decision.getCompletedCount();

        telegramBotService.editMessageText(
                messageId, String.format("⏳ Заказ #%d: оформляем рефанд...", orderId));

        TelegramCallbackTxService.CancelResult res;
        try {
            res = callbackTxService.performCancelTx(orderId, completedCount);
        } catch (Exception e) {
            log.error("Transactional cancel failed for order {}: {}", orderId, e.getMessage(), e);
            telegramBotService.editMessageText(
                    messageId,
                    String.format("❌ Заказ #%d: ошибка при рефанде. Проверьте админку.", orderId));
            return;
        }

        if (!res.processed()) {
            telegramBotService.editMessageText(
                    messageId, String.format("ℹ️ Заказ #%d: %s", orderId, res.reason()));
            return;
        }

        // Best-effort bot signal — DB state is already correct regardless of this outcome.
        String botOrderId = decision.getBotOrderId();
        if (botOrderId != null && !botOrderId.isBlank()) {
            try {
                instagramBotClient.cancelOrderFast(botOrderId);
            } catch (Exception e) {
                log.warn("cancelOrderFast failed for bot order {}: {}", botOrderId, e.getMessage());
            }
        }

        // Record profit from the delivered portion — outside the tx so Redis writes don't retry
        // if the DB tx failed. Matches the behaviour of auto webhook path (notifyOrderPartial).
        if (res.partial() && res.profitAmount().compareTo(BigDecimal.ZERO) > 0) {
            try {
                dailyProfitService.recordProfit(res.profitAmount(), OrderStatus.PARTIAL);
            } catch (Exception e) {
                log.warn("Profit recording failed for order {}: {}", orderId, e.getMessage());
            }
        }

        String finalText;
        if (res.partial()) {
            int completed = completedCount != null ? completedCount : 0;
            int original = decision.getOriginalCount() != null ? decision.getOriginalCount() : 0;
            finalText =
                    String.format(
                            "⚠️ Заказ #%d частично отменён\nВыполнено: %d из %d\nРефанд за"
                                    + " невыполненное: $%s",
                            orderId, completed, original, res.refundedAmount().toPlainString());
        } else {
            finalText =
                    String.format(
                            "❌ Заказ #%d отменён (0 выполнено). Полный рефанд: $%s",
                            orderId, res.refundedAmount().toPlainString());
        }
        telegramBotService.editMessageText(messageId, finalText);
        log.info(
                "Admin CANCEL order={} partial={} refund={}",
                orderId,
                res.partial(),
                res.refundedAmount());
    }
}

package com.smmpanel.service.notification;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.telegram.TelegramCallbackQuery;
import com.smmpanel.dto.telegram.TelegramUpdate;
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
 *   <li>Transactional DB work (refund + status) is delegated to {@link TelegramCallbackTxService}
 *       — a separate bean so @Transactional is applied through the AOP proxy.
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
        // Nothing that can block should run before this.
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
            log.error(
                    "Callback handler failed for update_id={}: {}", updateId, e.getMessage(), e);
            telegramBotService.editMessageText(
                    messageId, "⚠️ Ошибка обработки. Проверьте логи SMM-панели.");
        }
    }

    private void handleProceed(Long orderId, Integer messageId) {
        Optional<String> botOrderIdOpt = callbackTxService.readBotOrderIdIfPending(orderId);
        if (botOrderIdOpt.isEmpty()) {
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
        String botOrderId = botOrderIdOpt.get();
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
        Optional<String> botOrderIdOpt = callbackTxService.readBotOrderIdIfPending(orderId);
        if (botOrderIdOpt.isEmpty()) {
            telegramBotService.editMessageText(
                    messageId,
                    String.format("ℹ️ Заказ #%d: решение уже принято или истекло", orderId));
            return;
        }

        telegramBotService.editMessageText(
                messageId,
                String.format("⏳ Заказ #%d: отменяем и оформляем рефанд...", orderId));

        TelegramCallbackTxService.CancelResult res;
        try {
            res = callbackTxService.performCancelTx(orderId);
        } catch (Exception e) {
            log.error(
                    "Transactional cancel failed for order {}: {}", orderId, e.getMessage(), e);
            telegramBotService.editMessageText(
                    messageId,
                    String.format(
                            "❌ Заказ #%d: ошибка при рефанде. Проверьте админку.", orderId));
            return;
        }

        if (!res.processed()) {
            telegramBotService.editMessageText(
                    messageId, String.format("ℹ️ Заказ #%d: %s", orderId, res.reason()));
            return;
        }

        // Best-effort bot signal — the DB state is already correct regardless of this outcome.
        String botOrderId = botOrderIdOpt.get();
        if (botOrderId != null && !botOrderId.isBlank()) {
            try {
                instagramBotClient.cancelOrderFast(botOrderId);
            } catch (Exception e) {
                log.warn("cancelOrderFast failed for bot order {}: {}", botOrderId, e.getMessage());
            }
        }

        telegramBotService.editMessageText(
                messageId,
                String.format(
                        "❌ Заказ #%d отменён. Рефанд: $%s",
                        orderId, res.refundedAmount().toPlainString()));
        log.info("Admin CANCEL order={} refund={}", orderId, res.refundedAmount());
    }
}

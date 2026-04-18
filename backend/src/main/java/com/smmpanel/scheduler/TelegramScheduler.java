package com.smmpanel.scheduler;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.config.TelegramBotProperties;
import com.smmpanel.dto.telegram.CancelPendingDecision;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.integration.InstagramService;
import com.smmpanel.service.notification.CancelDecisionService;
import com.smmpanel.service.notification.DailyProfitService;
import com.smmpanel.service.notification.TelegramBotService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramScheduler {

    private final DailyProfitService dailyProfitService;
    private final TelegramBotService telegramBotService;
    private final CancelDecisionService cancelDecisionService;
    private final OrderRepository orderRepository;
    private final InstagramService instagramService;
    private final TelegramBotProperties telegramBotProperties;
    private final InstagramBotClient instagramBotClient;

    /** Send daily profit report at 23:55. */
    @Scheduled(cron = "0 55 23 * * *")
    public void sendDailyReport() {
        if (!telegramBotProperties.isEnabled()) return;
        try {
            String report = dailyProfitService.buildDailyReportText();
            telegramBotService.sendPlainMessage(report);
            dailyProfitService.persistDailyReport();
            log.info("Daily profit report sent and persisted");
        } catch (Exception e) {
            log.error("Failed to send daily profit report: {}", e.getMessage(), e);
        }
    }

    /**
     * Check for expired cancel decisions every 10 minutes. Keys that have expired are already gone
     * from Redis. This processes decisions that are still pending.
     */
    @Scheduled(fixedRate = 600_000)
    @Transactional
    public void checkExpiredCancelDecisions() {
        if (!telegramBotProperties.isEnabled()) return;
        List<Long> pendingOrderIds = cancelDecisionService.getAllPendingOrderIds();
        if (pendingOrderIds.isEmpty()) return;

        log.debug("Checking {} pending cancel decisions", pendingOrderIds.size());

        String defaultAction = telegramBotProperties.getCancel().getDefaultAction();

        for (Long orderId : pendingOrderIds) {
            Optional<CancelPendingDecision> decisionOpt =
                    cancelDecisionService.getPendingDecision(orderId);
            if (decisionOpt.isEmpty()) continue; // Already expired or processed

            CancelPendingDecision decision = decisionOpt.get();
            int timeoutHours = telegramBotProperties.getCancel().getTimeoutHours();
            boolean expired =
                    decision.getCreatedAt() != null
                            && decision.getCreatedAt()
                                    .plusHours(timeoutHours)
                                    .isBefore(java.time.LocalDateTime.now());

            if (!expired) continue;

            log.info(
                    "Cancel decision for order {} has expired, applying default action: {}",
                    orderId,
                    defaultAction);

            // Remove inline keyboard buttons from the original Telegram message
            telegramBotService.removeInlineKeyboard(decision.getTelegramMessageId());
            cancelDecisionService.removePendingDecision(orderId);

            if ("cancel".equalsIgnoreCase(defaultAction)) {
                applyDefaultCancel(
                        orderId,
                        decision.getBotOrderId(),
                        decision.getCompletedCount(),
                        decision.getOriginalCount());
            } else {
                // Default: proceed — resume the paused order in the bot
                if (decision.getBotOrderId() != null && !decision.getBotOrderId().isBlank()) {
                    boolean resumed = instagramBotClient.resumeOrder(decision.getBotOrderId());
                    log.info(
                            "Auto-proceed timeout for order {} — bot resume: {}", orderId, resumed);
                }
                telegramBotService.sendPlainMessage(
                        String.format(
                                "⏰ Решение по заказу #%d истекло — заказ возобновлён автоматически",
                                orderId));
            }
        }
    }

    private void applyDefaultCancel(
            Long orderId, String botOrderId, Integer completedCount, Integer originalCount) {
        Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isEmpty()) return;
        Order order = orderOpt.get();
        // Race guard: if an admin/webhook already terminated the order, don't double-refund.
        if (order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.PARTIAL
                || order.getStatus() == OrderStatus.COMPLETED) {
            log.info(
                    "Order {} already in terminal status {} — skipping auto-cancel",
                    orderId,
                    order.getStatus());
            return;
        }

        if (botOrderId != null && !botOrderId.isBlank()) {
            instagramBotClient.cancelOrder(botOrderId);
        }

        int completed = completedCount != null && completedCount > 0 ? completedCount : 0;
        int quantity = order.getQuantity() != null ? order.getQuantity() : 0;

        BigDecimal refunded;
        boolean isPartial;

        if (completed > 0 && completed < quantity) {
            refunded =
                    instagramService.processPartialRefund(
                            order,
                            completed,
                            String.format(
                                    "Partial auto-refund for Instagram order #%d: %d/%d delivered"
                                            + " (timeout)",
                                    orderId, completed, quantity));
            order.setStatus(OrderStatus.PARTIAL);
            order.setTrafficStatus("PARTIAL_CANCELLED_TIMEOUT");
            order.setViewsDelivered(completed);
            order.setRemains(quantity - completed);
            isPartial = true;
        } else {
            refunded =
                    instagramService.processFullRefund(
                            order,
                            String.format(
                                    "Full auto-refund for Instagram order #%d: no items delivered"
                                            + " (timeout)",
                                    orderId));
            order.setStatus(OrderStatus.CANCELLED);
            order.setTrafficStatus("CANCELLED_TIMEOUT");
            order.setViewsDelivered(0);
            order.setRemains(quantity);
            isPartial = false;
        }

        BigDecimal profit = order.getCharge() != null ? order.getCharge() : BigDecimal.ZERO;
        orderRepository.save(order);

        // Profit from delivered portion (safe here — scheduler @Transactional commits on return).
        if (isPartial && profit.compareTo(BigDecimal.ZERO) > 0) {
            try {
                dailyProfitService.recordProfit(profit, OrderStatus.PARTIAL);
            } catch (Exception e) {
                log.warn(
                        "Profit recording failed for auto-cancel order {}: {}",
                        orderId,
                        e.getMessage());
            }
        }

        String msg;
        if (isPartial) {
            msg =
                    String.format(
                            "⏰ Решение по заказу #%d истекло — частично отменён (выполнено %d из"
                                    + " %d, рефанд за невыполненное: $%s)",
                            orderId,
                            completed,
                            originalCount != null ? originalCount : quantity,
                            refunded.toPlainString());
        } else {
            msg =
                    String.format(
                            "⏰ Решение по заказу #%d истекло — заказ автоматически отменён"
                                    + " (полный рефанд: $%s)",
                            orderId, refunded.toPlainString());
        }
        telegramBotService.sendPlainMessage(msg);
        log.info("Auto-cancel timeout order={} partial={} refund={}", orderId, isPartial, refunded);
    }
}

package com.smmpanel.consumer;

import com.smmpanel.config.RabbitMQConfig;
import com.smmpanel.dto.instagram.InstagramResultMessage;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.balance.BalanceService;
import com.smmpanel.service.notification.TelegramNotificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes Instagram order results from RabbitMQ.
 *
 * <p>Both Korean and German bots publish results to the same instagram.results queue. This consumer
 * updates the panel order status based on the bot's result.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstagramResultConsumer {

    private final OrderRepository orderRepository;
    private final TelegramNotificationService telegramNotificationService;
    private final BalanceService balanceService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_RESULTS)
    @Transactional
    public void handleResult(InstagramResultMessage result) {
        log.info(
                "Received Instagram result from RabbitMQ: externalId={}, status={}, completed={},"
                        + " failed={}",
                result.getExternalId(),
                result.getStatus(),
                result.getCompleted(),
                result.getFailed());

        try {
            // Find order by external_id (which is our panel order ID)
            Long orderId = Long.parseLong(result.getExternalId());
            Order order =
                    orderRepository
                            .findById(orderId)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Order not found: " + orderId));

            // Update order with result data
            updateOrderFromResult(order, result);

            orderRepository.save(order);

            log.info(
                    "Updated order {} from Instagram result: status={}, remains={}",
                    orderId,
                    order.getStatus(),
                    order.getRemains());

            // Send Telegram notification for terminal states
            if (order.getStatus() == OrderStatus.COMPLETED) {
                telegramNotificationService.notifyOrderCompleted(order, result.getCompleted());
            } else if (order.getStatus() == OrderStatus.PARTIAL
                    || order.getStatus() == OrderStatus.CANCELLED) {
                telegramNotificationService.notifyOrderFailed(order, result.getCompleted());
            }

        } catch (NumberFormatException e) {
            log.error(
                    "Invalid external_id format in Instagram result: {}",
                    result.getExternalId(),
                    e);
        } catch (Exception e) {
            log.error(
                    "Failed to process Instagram result for order {}: {}",
                    result.getExternalId(),
                    e.getMessage(),
                    e);
            // Don't rethrow - we don't want to requeue the message
        }
    }

    private void updateOrderFromResult(Order order, InstagramResultMessage result) {
        // Update counts
        if (result.getStartLikeCount() != null) {
            order.setStartLikeCount(result.getStartLikeCount());
            // Also update generic startCount for frontend display
            if (result.getStartLikeCount() > 0) {
                order.setStartCount(result.getStartLikeCount());
            }
        }
        if (result.getCurrentLikeCount() != null) {
            order.setCurrentLikeCount(result.getCurrentLikeCount());
        }
        if (result.getStartCommentCount() != null) {
            order.setStartCommentCount(result.getStartCommentCount());
            // Also update generic startCount for frontend display
            if (result.getStartCommentCount() > 0
                    && (order.getStartCount() == null || order.getStartCount() == 0)) {
                order.setStartCount(result.getStartCommentCount());
            }
        }
        if (result.getCurrentCommentCount() != null) {
            order.setCurrentCommentCount(result.getCurrentCommentCount());
        }
        if (result.getStartFollowerCount() != null) {
            order.setStartFollowerCount(result.getStartFollowerCount());
            // Also update generic startCount for frontend display
            if (result.getStartFollowerCount() > 0
                    && (order.getStartCount() == null || order.getStartCount() == 0)) {
                order.setStartCount(result.getStartFollowerCount());
            }
        }
        if (result.getCurrentFollowerCount() != null) {
            order.setCurrentFollowerCount(result.getCurrentFollowerCount());
        }

        // Calculate remains
        int completed = result.getCompleted();
        int remains = order.getQuantity() - completed;
        order.setRemains(Math.max(0, remains));

        // Update status and process refund based on result
        OrderStatus newStatus =
                determineStatusAndProcessRefund(order, result.getStatus(), completed);
        order.setStatus(newStatus);

        // Update error message if failed
        if (result.getError() != null && !result.getError().isBlank()) {
            order.setErrorMessage(result.getError());
        }

        // Update timestamp
        order.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * Determines the final order status and processes refund if needed.
     *
     * <p>Logic for "failed" status from bot: - If completed > 0: PARTIAL status, refund for
     * undelivered items - If completed == 0: CANCELLED status, full refund
     */
    private OrderStatus determineStatusAndProcessRefund(
            Order order, String botStatus, int completed) {
        if (botStatus == null) {
            return OrderStatus.PROCESSING;
        }

        return switch (botStatus.toLowerCase()) {
            case "completed" -> OrderStatus.COMPLETED;

            case "failed" -> {
                if (completed > 0) {
                    // Partial delivery - refund for undelivered items
                    processPartialRefund(order, completed);
                    yield OrderStatus.PARTIAL;
                } else {
                    // No delivery at all - full refund and cancel
                    processFullRefund(order);
                    yield OrderStatus.CANCELLED;
                }
            }

            case "partial" -> {
                // Bot explicitly says partial
                if (completed > 0) {
                    processPartialRefund(order, completed);
                    yield OrderStatus.PARTIAL;
                } else {
                    processFullRefund(order);
                    yield OrderStatus.CANCELLED;
                }
            }

            case "processing", "in_progress" -> OrderStatus.PROCESSING;
            case "cancelled" -> {
                processFullRefund(order);
                yield OrderStatus.CANCELLED;
            }
            default -> OrderStatus.PROCESSING;
        };
    }

    /** Process partial refund for undelivered items. Refund = charge * (remains / quantity) */
    private void processPartialRefund(Order order, int completed) {
        if (order.getCharge() == null || order.getQuantity() == null || order.getQuantity() == 0) {
            log.warn(
                    "Cannot calculate partial refund for order {}: missing charge or quantity",
                    order.getId());
            return;
        }

        int remains = order.getQuantity() - completed;
        if (remains <= 0) {
            log.info("No refund needed for order {} - all items delivered", order.getId());
            return;
        }

        // Calculate proportional refund: charge * (remains / quantity)
        BigDecimal refundAmount =
                order.getCharge()
                        .multiply(BigDecimal.valueOf(remains))
                        .divide(BigDecimal.valueOf(order.getQuantity()), 4, RoundingMode.HALF_UP);

        if (refundAmount.compareTo(BigDecimal.ZERO) > 0 && order.getUser() != null) {
            String reason =
                    String.format(
                            "Partial refund for Instagram order #%d: %d/%d delivered",
                            order.getId(), completed, order.getQuantity());
            balanceService.refund(order.getUser(), refundAmount, order, reason);
            log.info("Processed partial refund of {} for order {}", refundAmount, order.getId());
        }
    }

    /** Process full refund for cancelled/failed orders with no delivery. */
    private void processFullRefund(Order order) {
        if (order.getCharge() == null || order.getCharge().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Cannot process full refund for order {}: no charge", order.getId());
            return;
        }

        if (order.getUser() != null) {
            String reason =
                    String.format(
                            "Full refund for Instagram order #%d: no items delivered",
                            order.getId());
            balanceService.refund(order.getUser(), order.getCharge(), order, reason);
            log.info("Processed full refund of {} for order {}", order.getCharge(), order.getId());
        }
    }
}

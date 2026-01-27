package com.smmpanel.consumer;

import com.smmpanel.config.RabbitMQConfig;
import com.smmpanel.dto.instagram.InstagramResultMessage;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import com.smmpanel.service.notification.TelegramNotificationService;
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

            // Send Telegram notification for completed/failed orders
            if (order.getStatus() == OrderStatus.COMPLETED) {
                telegramNotificationService.notifyOrderCompleted(order, result.getCompleted());
            } else if (order.getStatus() == OrderStatus.ERROR
                    || order.getStatus() == OrderStatus.PARTIAL) {
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
        int failed = result.getFailed();
        int remains = order.getQuantity() - completed;
        order.setRemains(Math.max(0, remains));

        // Update status based on result
        OrderStatus newStatus = mapResultStatusToOrderStatus(result.getStatus(), completed, failed);
        order.setStatus(newStatus);

        // Update error message if failed
        if (result.getError() != null && !result.getError().isBlank()) {
            order.setErrorMessage(result.getError());
        }

        // Update timestamp
        order.setUpdatedAt(LocalDateTime.now());
    }

    private OrderStatus mapResultStatusToOrderStatus(
            String resultStatus, int completed, int failed) {
        if (resultStatus == null) {
            return OrderStatus.PROCESSING;
        }

        return switch (resultStatus.toLowerCase()) {
            case "completed" -> OrderStatus.COMPLETED;
            case "failed" -> OrderStatus.ERROR;
            case "partial" -> {
                // If some completed but also some failed
                if (completed > 0 && failed > 0) {
                    yield OrderStatus.PARTIAL;
                } else if (completed > 0) {
                    yield OrderStatus.COMPLETED;
                } else {
                    yield OrderStatus.ERROR;
                }
            }
            case "processing", "in_progress" -> OrderStatus.PROCESSING;
            case "cancelled" -> OrderStatus.CANCELLED;
            default -> OrderStatus.PROCESSING;
        };
    }
}

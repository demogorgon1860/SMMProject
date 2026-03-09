package com.smmpanel.scheduler;

import com.smmpanel.client.InstagramBotClient;
import com.smmpanel.dto.instagram.InstagramOrderStatus;
import com.smmpanel.entity.Order;
import com.smmpanel.entity.OrderStatus;
import com.smmpanel.repository.jpa.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled task that polls the Instagram bot every 15 minutes to update order progress (remains
 * field). Fetches all bot orders in a single request and matches them to panel orders by
 * external_id.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstagramProgressScheduler {

    private final OrderRepository orderRepository;
    private final InstagramBotClient instagramBotClient;

    @Scheduled(fixedRateString = "${app.instagram.progress.poll-interval-ms:900000}")
    @Transactional
    public void updateInstagramOrderProgress() {
        List<Order> activeOrders =
                orderRepository.findByStatusIn(
                        List.of(
                                OrderStatus.IN_PROGRESS,
                                OrderStatus.PROCESSING,
                                OrderStatus.ACTIVE));

        List<Order> instagramOrders =
                activeOrders.stream().filter(o -> o.getInstagramBotOrderId() != null).toList();

        if (instagramOrders.isEmpty()) {
            return;
        }

        log.info("Polling Instagram bot for progress on {} active orders", instagramOrders.size());

        try {
            List<InstagramOrderStatus> botOrders = instagramBotClient.getAllOrders();

            // Build lookup map: external_id -> bot order status
            Map<String, InstagramOrderStatus> botOrdersByExternalId =
                    botOrders.stream()
                            .filter(o -> o.getExternalId() != null && !o.getExternalId().isEmpty())
                            .collect(
                                    Collectors.toMap(
                                            InstagramOrderStatus::getExternalId,
                                            Function.identity(),
                                            (a, b) -> b));

            int updated = 0;
            for (Order order : instagramOrders) {
                try {
                    String orderId = order.getId().toString();
                    InstagramOrderStatus botStatus = botOrdersByExternalId.get(orderId);

                    if (botStatus == null || botStatus.getCompleted() == null) {
                        continue;
                    }

                    int completed = botStatus.getCompleted();
                    int newRemains = Math.max(0, order.getQuantity() - completed);

                    if (!Integer.valueOf(newRemains).equals(order.getRemains())) {
                        order.setRemains(newRemains);
                        orderRepository.save(order);
                        updated++;
                        log.debug(
                                "Order {} progress: {}/{} completed, remains: {}",
                                order.getId(),
                                completed,
                                order.getQuantity(),
                                newRemains);
                    }
                } catch (Exception e) {
                    log.warn(
                            "Failed to process progress for order {}: {}",
                            order.getId(),
                            e.getMessage());
                }
            }

            if (updated > 0) {
                log.info("Updated remains for {} Instagram orders", updated);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch orders from Instagram bot: {}", e.getMessage());
        }
    }
}

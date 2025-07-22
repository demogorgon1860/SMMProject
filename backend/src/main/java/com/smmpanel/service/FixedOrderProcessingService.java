package com.smmpanel.service;

import com.smmpanel.entity.Order;
import com.smmpanel.enums.OrderStatus;
import com.smmpanel.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Обновленный OrderProcessingService с интеграцией назначения офферов
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FixedOrderProcessingService {

    private final OrderRepository orderRepository;
    private final FixedVideoProcessingService videoProcessingService;
    private final OfferEventProducer offerEventProducer;

    /**
     * Обработка нового заказа с автоматическим назначением оффера (если клип не нужен)
     */
    @Transactional
    public void processNewOrder(Long orderId) {
        try {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            // Проверяем, нужно ли создавать клип
            boolean needsClipCreation = shouldCreateClip(order);

            if (needsClipCreation) {
                // Запускаем обработку видео (клип будет создан)
                videoProcessingService.completeVideoProcessing(orderId, order.getLink(), true);
                log.info("Started video processing for order {} (clip creation)", orderId);
            } else {
                // Сразу назначаем оффер с оригинальной ссылкой
                assignOfferDirectly(order);
                log.info("Assigned offer directly for order {} (no clip needed)", orderId);
            }

        } catch (Exception e) {
            log.error("Failed to process new order {}: {}", orderId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Прямое назначение оффера без создания клипа
     */
    private void assignOfferDirectly(Order order) {
        try {
            String offerName = "SMM_ORIGINAL_Order_" + order.getId() + "_" + System.currentTimeMillis();
            
            // Отправляем событие для назначения оффера с оригинальной ссылкой
            offerEventProducer.sendOfferAssignmentEvent(
                    order.getId(),
                    offerName,
                    order.getLink(),
                    "DIRECT_ASSIGNMENT"
            );

            // Обновляем статус заказа
            order.setStatus(OrderStatus.PROCESSING);
            orderRepository.save(order);

        } catch (Exception e) {
            log.error("Failed to assign offer directly for order {}: {}", order.getId(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Определение необходимости создания клипа
     * (упрощенная логика - можно расширить)
     */
    private boolean shouldCreateClip(Order order) {
        // В упрощенной версии всегда создаем клипы для лучшей конверсии
        // В реальности здесь может быть более сложная логика
        return true;
    }
}

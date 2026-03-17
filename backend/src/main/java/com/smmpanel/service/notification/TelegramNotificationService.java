package com.smmpanel.service.notification;

import com.smmpanel.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Facade over TelegramBotService. All callers (OrderService, InstagramService,
 * InstagramResultConsumer) use this class — zero changes needed in call sites.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final TelegramBotService telegramBotService;

    public void notifyNewOrder(Order order) {
        telegramBotService.notifyNewOrder(order);
    }

    public void notifyOrderCompleted(Order order, Integer completed) {
        telegramBotService.notifyOrderCompleted(order, completed);
    }

    public void notifyOrderPartial(Order order, Integer completed) {
        telegramBotService.notifyOrderPartial(order, completed);
    }

    public void notifyOrderFailed(Order order, Integer completed) {
        telegramBotService.notifyOrderFailed(order, completed);
    }

    public void notifyOrderCancelledPending(Order order) {
        telegramBotService.notifyOrderCancelledPending(order);
    }
}
